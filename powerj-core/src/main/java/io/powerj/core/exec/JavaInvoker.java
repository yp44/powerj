package io.powerj.core.exec;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import io.powerj.api.ScriptBlock;

/**
 * Appels Java depuis le shell (spécification FR-48 à FR-50, FR-53) : méthodes d'instance et statiques,
 * constructeurs. Parmi les surcharges applicables (avec varargs), celle qui demande les conversions les
 * moins coûteuses est choisie, puis la plus spécifique ; une égalité restante est une ambiguïté.
 */
final class JavaInvoker {

    /** Conversion impossible. */
    static final int NO_MATCH = Integer.MAX_VALUE;

    private static final List<Class<?>> NUMERIC_ORDER = List.of(byte.class, short.class, int.class, long.class,
            float.class, double.class);

    /** Méthodes publiques d'instance par type et par nom, vues à travers les types accessibles. */
    private static final ClassValue<Map<String, List<Method>>> INSTANCE_METHODS = new ClassValue<>() {
        @Override
        protected Map<String, List<Method>> computeValue(Class<?> type) {
            Map<String, List<Method>> byName = new LinkedHashMap<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (Class<?> t : Members.accessibleTypes(type)) {
                for (Method m : t.getMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) && Members.isAccessible(m.getDeclaringClass())
                            && seen.putIfAbsent(signature(m), true) == null) {
                        byName.computeIfAbsent(m.getName(), _ -> new ArrayList<>()).add(m);
                    }
                }
            }
            return byName;
        }
    };

    private JavaInvoker() {
    }

    /** Méthodes publiques d'instance accessibles d'un type, par nom (complétion). */
    static Map<String, List<Method>> instanceMethods(Class<?> type) {
        return INSTANCE_METHODS.get(type);
    }

    /** Le type a-t-il une méthode publique {@code name()} sans argument ? (aide des messages d'erreur) */
    static boolean hasNoArgMethod(Class<?> type, String name) {
        return INSTANCE_METHODS.get(type).getOrDefault(name, List.of()).stream().anyMatch(m -> m.getParameterCount() == 0);
    }

    /** {@code cible.nom(arguments)}. */
    static Object invokeVirtual(Object target, String name, List<Object> args) {
        if (target == null) {
            throw new PjException("appel de " + name + "() sur une valeur nulle");
        }
        List<Method> candidates = INSTANCE_METHODS.get(target.getClass()).getOrDefault(name, List.of());
        if (candidates.isEmpty()) {
            throw new PjException(target.getClass().getSimpleName() + " n'a pas de méthode " + name + "()");
        }
        Method method = (Method) select(candidates, args, name);
        return call(method, target, convertAll(method, args));
    }

    /** {@code Classe.nom(arguments)}. */
    static Object invokeStatic(Class<?> type, String name, List<Object> args) {
        List<Executable> candidates = new ArrayList<>();
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())
                    && JavaClasses.accessible(m.getDeclaringClass()) && m.getDeclaringClass() == type) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            for (Method m : type.getMethods()) { // méthodes statiques héritées d'une superclasse
                if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())
                        && JavaClasses.accessible(m.getDeclaringClass())) {
                    candidates.add(m);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new PjException(type.getSimpleName() + " n'a pas de méthode statique " + name + "()");
        }
        Method method = (Method) select(candidates, args, name);
        return call(method, null, convertAll(method, args));
    }

    /** {@code new Classe(arguments)}. */
    static Object construct(Class<?> type, List<Object> args) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            throw new PjException("new " + type.getSimpleName() + " : classe abstraite ou interface");
        }
        List<Executable> candidates = new ArrayList<>(List.of(type.getConstructors()));
        if (candidates.isEmpty()) {
            throw new PjException("new " + type.getSimpleName() + " : aucun constructeur public");
        }
        var constructor = (Constructor<?>) select(candidates, args, "new " + type.getSimpleName());
        try {
            return constructor.newInstance(convertAll(constructor, args));
        } catch (InvocationTargetException e) {
            throw javaException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new PjException(PjError.of("new " + type.getSimpleName() + " impossible : " + e.getMessage(), e));
        }
    }

    /** Exception levée par du code Java : erreur bloquante courte (FR-53). */
    static RuntimeException javaException(Throwable cause) {
        if (cause instanceof PjException || cause instanceof CancellationException) {
            return (RuntimeException) cause; // levée par un bloc { } appelé depuis Java
        }
        String message = cause.getMessage();
        return new PjException(PjError.of(cause.getClass().getName() + (message == null ? "" : " : " + message),
                cause));
    }

    private static Object call(Method method, Object target, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw javaException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new PjException(PjError.of(method.getName() + "() inaccessible : " + e.getMessage(), e));
        }
    }

    // --- Choix de la surcharge ---

    private record Match(Executable executable, int cost, boolean varargs) { }

    private static Executable select(List<? extends Executable> candidates, List<Object> args, String name) {
        List<Match> matches = new ArrayList<>();
        for (Executable e : candidates) {
            if (e.getParameterCount() == args.size()) {
                int cost = cost(e.getParameterTypes(), args, false);
                if (cost != NO_MATCH) {
                    matches.add(new Match(e, cost, false));
                }
            }
        }
        if (matches.isEmpty()) {
            for (Executable e : candidates) {
                if (e.isVarArgs() && args.size() >= e.getParameterCount() - 1) {
                    int cost = cost(e.getParameterTypes(), args, true);
                    if (cost != NO_MATCH) {
                        matches.add(new Match(e, cost, true));
                    }
                }
            }
        }
        if (matches.isEmpty()) {
            throw new PjException("aucune surcharge de " + name + " ne correspond aux arguments ("
                    + String.join(", ", args.stream().map(JavaInvoker::typeName).toList()) + ") ; disponibles : "
                    + String.join(", ", candidates.stream().map(JavaInvoker::signature).distinct().toList()));
        }
        int best = matches.stream().mapToInt(Match::cost).min().orElseThrow();
        List<Match> cheapest = matches.stream().filter(m -> m.cost() == best).toList();
        List<Match> maximal = cheapest.stream()
                .filter(m -> cheapest.stream().allMatch(o -> o == m || moreSpecific(m.executable(), o.executable())))
                .toList();
        if (maximal.isEmpty()) {
            throw new PjException("appel ambigu de " + name + " : " + String.join(", ",
                    cheapest.stream().map(m -> signature(m.executable())).toList()));
        }
        return maximal.getFirst().executable();
    }

    private static int cost(Class<?>[] params, List<Object> args, boolean varargs) {
        int fixed = varargs ? params.length - 1 : params.length;
        long total = 0;
        for (int i = 0; i < fixed; i++) {
            int c = cost(params[i], args.get(i));
            if (c == NO_MATCH) {
                return NO_MATCH;
            }
            total += c;
        }
        if (varargs) {
            Class<?> component = params[params.length - 1].componentType();
            for (int i = fixed; i < args.size(); i++) {
                int c = cost(component, args.get(i));
                if (c == NO_MATCH) {
                    return NO_MATCH;
                }
                total += c;
            }
            total += 20;
        }
        return (int) Math.min(total, NO_MATCH - 1);
    }

    private static boolean moreSpecific(Executable a, Executable b) {
        Class<?>[] pa = a.getParameterTypes();
        Class<?>[] pb = b.getParameterTypes();
        if (pa.length != pb.length) {
            return pa.length > pb.length; // varargs : la forme la plus longue est plus précise
        }
        for (int i = 0; i < pa.length; i++) {
            if (!subtype(pa[i], pb[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean subtype(Class<?> a, Class<?> b) {
        if (a == b || b.isAssignableFrom(a)) {
            return true;
        }
        if (a.isPrimitive() && b.isPrimitive()) {
            int ia = NUMERIC_ORDER.indexOf(a);
            int ib = NUMERIC_ORDER.indexOf(b);
            return a == char.class ? ib >= NUMERIC_ORDER.indexOf(int.class) : ia >= 0 && ib > ia;
        }
        return a.isPrimitive() && b.isAssignableFrom(box(a));
    }

    // --- Conversions (FR-50) ---

    /** Coût de conversion d'une valeur vers un type de paramètre, ou {@link #NO_MATCH}. */
    static int cost(Class<?> type, Object value) {
        if (value == null) {
            return type.isPrimitive() ? NO_MATCH : 2;
        }
        Class<?> boxed = box(type);
        if (value.getClass() == boxed) {
            return type.isPrimitive() ? 1 : 0;
        }
        if (boxed.isInstance(value)) {
            return 2;
        }
        if (value instanceof Character c) {
            if (isNumeric(boxed)) {
                return widens(int.class, unbox(boxed)) ? 4 : NO_MATCH;
            }
            return boxed == String.class || boxed == CharSequence.class ? 9 : NO_MATCH;
        }
        if (value instanceof Number n) {
            return numberCost(n, boxed);
        }
        if (value instanceof String s) {
            if (boxed == Character.class) {
                return s.length() == 1 ? 6 : NO_MATCH;
            }
            if (boxed == Path.class || boxed == File.class) {
                return 7;
            }
            if (boxed.isEnum()) {
                return enumConstant(boxed, s) != null ? 7 : NO_MATCH;
            }
            return NO_MATCH;
        }
        if (value instanceof ScriptBlock) {
            return FunctionalAdapter.isFunctional(type) ? 6 : NO_MATCH;
        }
        if (type.isArray() && value instanceof Collection<?> items) {
            for (Object item : items) {
                if (cost(type.componentType(), item) == NO_MATCH) {
                    return NO_MATCH;
                }
            }
            return 7;
        }
        return NO_MATCH;
    }

    private static int numberCost(Number n, Class<?> boxed) {
        Class<?> source = unbox(n.getClass());
        if (isNumeric(boxed)) {
            Class<?> target = unbox(boxed);
            if (source != null && widens(source, target)) {
                return 3 + NUMERIC_ORDER.indexOf(target) - NUMERIC_ORDER.indexOf(source);
            }
            if (isIntegral(n) && (target == int.class || target == long.class || target == short.class
                    || target == byte.class)) {
                return fits(n, target) ? 9 : NO_MATCH; // sans perte uniquement
            }
            if (target == float.class && (n instanceof Double || n instanceof BigDecimal)) {
                return 9;
            }
            if (target == double.class && (n instanceof BigDecimal || n instanceof BigInteger)) {
                return 9;
            }
            return NO_MATCH;
        }
        if (boxed == BigInteger.class) {
            return isIntegral(n) ? 8 : NO_MATCH;
        }
        if (boxed == BigDecimal.class) {
            return 8;
        }
        return NO_MATCH;
    }

    /** Convertit une valeur dont le coût de conversion est connu. */
    static Object convert(Class<?> type, Object value) {
        if (value == null) {
            return null;
        }
        Class<?> boxed = box(type);
        if (boxed.isInstance(value)) {
            return value;
        }
        return switch (value) {
            case Character c when isNumeric(boxed) -> number(boxed, (int) c);
            case Character c -> String.valueOf(c);
            case Number n -> number(boxed, n);
            case String s when boxed == Character.class -> s.charAt(0);
            case String s when boxed == Path.class -> Path.of(s);
            case String s when boxed == File.class -> new File(s);
            case String s when boxed.isEnum() -> enumConstant(boxed, s);
            case ScriptBlock block -> FunctionalAdapter.adapt(block, type);
            case Collection<?> items when type.isArray() -> {
                Object array = Array.newInstance(type.componentType(), items.size());
                int i = 0;
                for (Object item : items) {
                    Array.set(array, i++, convert(type.componentType(), item));
                }
                yield array;
            }
            default -> throw new PjException("conversion impossible de " + typeName(value) + " en " + type.getSimpleName());
        };
    }

    private static Object[] convertAll(Executable executable, List<Object> args) {
        Class<?>[] params = executable.getParameterTypes();
        boolean varargs = executable.isVarArgs() && !(args.size() == params.length
                && cost(params[params.length - 1], args.getLast()) != NO_MATCH);
        Object[] converted = new Object[params.length];
        int fixed = varargs ? params.length - 1 : params.length;
        for (int i = 0; i < fixed; i++) {
            converted[i] = convert(params[i], args.get(i));
        }
        if (varargs) {
            Class<?> component = params[params.length - 1].componentType();
            Object rest = Array.newInstance(component, args.size() - fixed);
            for (int i = fixed; i < args.size(); i++) {
                Array.set(rest, i - fixed, convert(component, args.get(i)));
            }
            converted[params.length - 1] = rest;
        }
        return converted;
    }

    private static Object number(Class<?> boxed, Number n) {
        if (boxed == Integer.class) {
            return n.intValue();
        }
        if (boxed == Long.class) {
            return n.longValue();
        }
        if (boxed == Double.class) {
            return n.doubleValue();
        }
        if (boxed == Float.class) {
            return n.floatValue();
        }
        if (boxed == Short.class) {
            return n.shortValue();
        }
        if (boxed == Byte.class) {
            return n.byteValue();
        }
        if (boxed == BigInteger.class) {
            return n instanceof BigInteger b ? b : BigInteger.valueOf(n.longValue());
        }
        if (boxed == BigDecimal.class) {
            return n instanceof BigDecimal d ? d : new BigDecimal(n.toString());
        }
        return n;
    }

    private static boolean fits(Number n, Class<?> target) {
        BigInteger value = n instanceof BigInteger b ? b : BigInteger.valueOf(n.longValue());
        long min = target == byte.class ? Byte.MIN_VALUE : target == short.class ? Short.MIN_VALUE
                : target == int.class ? Integer.MIN_VALUE : Long.MIN_VALUE;
        long max = target == byte.class ? Byte.MAX_VALUE : target == short.class ? Short.MAX_VALUE
                : target == int.class ? Integer.MAX_VALUE : Long.MAX_VALUE;
        return value.compareTo(BigInteger.valueOf(min)) >= 0 && value.compareTo(BigInteger.valueOf(max)) <= 0;
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger;
    }

    private static boolean widens(Class<?> source, Class<?> target) {
        int s = NUMERIC_ORDER.indexOf(source);
        int t = NUMERIC_ORDER.indexOf(target);
        return s >= 0 && t >= s;
    }

    private static boolean isNumeric(Class<?> boxed) {
        Class<?> primitive = unbox(boxed);
        return primitive != null && NUMERIC_ORDER.contains(primitive);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum) constant).name().equals(name)) {
                return constant;
            }
        }
        for (Object constant : type.getEnumConstants()) {
            if (((Enum) constant).name().equalsIgnoreCase(name)) {
                return constant;
            }
        }
        return null;
    }

    static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "boolean" -> Boolean.class;
            case "char" -> Character.class;
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            default -> Void.class;
        };
    }

    /** Type primitif d'une classe enveloppe ({@code Integer} → {@code int}), ou {@code null}. */
    private static Class<?> unbox(Class<?> type) {
        if (type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "java.lang.Integer" -> int.class;
            case "java.lang.Long" -> long.class;
            case "java.lang.Double" -> double.class;
            case "java.lang.Float" -> float.class;
            case "java.lang.Short" -> short.class;
            case "java.lang.Byte" -> byte.class;
            default -> null;
        };
    }

    static String signature(Executable e) {
        List<String> params = new ArrayList<>();
        Class<?>[] types = e.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            String name = types[i].getSimpleName();
            params.add(e.isVarArgs() && i == types.length - 1 ? name.replace("[]", "...") : name);
        }
        String name = e instanceof Constructor<?> c ? c.getDeclaringClass().getSimpleName() : e.getName();
        return name + "(" + String.join(", ", params) + ")";
    }

    static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
