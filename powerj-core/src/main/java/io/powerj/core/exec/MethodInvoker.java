package io.powerj.core.exec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Appel d'une méthode d'instance Java depuis un bloc : {@code $_.contains("b")},
 * {@code $_.name.endsWith(".java")}. Seules les méthodes publiques des types accessibles sont visibles
 * (voir {@link Members}). Parmi les surcharges de même nombre d'arguments, la moins coûteuse en conversions
 * est choisie.
 */
final class MethodInvoker {

    /** Pas de conversion possible. */
    private static final int NO_MATCH = Integer.MAX_VALUE;

    private MethodInvoker() {
    }

    static Object invoke(Object target, String name, List<Object> args) {
        if (target == null) {
            throw new PjException("appel de " + name + "() sur une valeur nulle");
        }
        Method best = null;
        int bestCost = NO_MATCH;
        Set<String> candidates = new LinkedHashSet<>();
        for (Method method : methods(target.getClass(), name)) {
            candidates.add(signature(method));
            if (method.getParameterCount() != args.size()) {
                continue;
            }
            int cost = 0;
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length && cost != NO_MATCH; i++) {
                int c = cost(types[i], args.get(i));
                cost = c == NO_MATCH ? NO_MATCH : cost + c;
            }
            if (cost < bestCost || cost == bestCost && best != null && moreSpecific(method, best)) {
                best = method;
                bestCost = cost;
            }
        }
        if (best == null) {
            if (candidates.isEmpty()) {
                throw new PjException(target.getClass().getSimpleName() + " n'a pas de méthode " + name + "()");
            }
            throw new PjException("aucune méthode " + name + " ne correspond aux arguments ("
                    + String.join(", ", args.stream().map(MethodInvoker::typeName).toList()) + ") ; disponibles : "
                    + String.join(", ", candidates));
        }
        Class<?>[] types = best.getParameterTypes();
        Object[] converted = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            converted[i] = convert(types[i], args.get(i));
        }
        try {
            return best.invoke(target, converted);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new PjException(PjError.of(cause.toString(), cause));
        } catch (IllegalAccessException e) {
            throw new PjException(PjError.of(name + "() inaccessible : " + e.getMessage(), e));
        }
    }

    /** Méthodes publiques d'instance nommées {@code name}, déclarées par un type accessible. */
    private static List<Method> methods(Class<?> type, String name) {
        List<Method> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Class<?> t : Members.accessibleTypes(type)) {
            for (Method m : t.getMethods()) {
                if (m.getName().equals(name) && !Modifier.isStatic(m.getModifiers())
                        && Members.isAccessible(m.getDeclaringClass()) && seen.add(signature(m))) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    private static boolean moreSpecific(Method candidate, Method current) {
        Class<?>[] a = candidate.getParameterTypes();
        Class<?>[] b = current.getParameterTypes();
        for (int i = 0; i < a.length; i++) {
            if (!b[i].isAssignableFrom(a[i])) {
                return false;
            }
        }
        return true;
    }

    /** Coût de conversion d'un argument vers un type de paramètre : 0 si direct, {@link #NO_MATCH} si impossible. */
    private static int cost(Class<?> type, Object value) {
        if (value == null) {
            return type.isPrimitive() ? NO_MATCH : 1;
        }
        Class<?> boxed = boxed(type);
        if (boxed.isInstance(value)) {
            return type.isPrimitive() ? 1 : 0;
        }
        if (value instanceof Character c) {
            if (isWideningTarget(boxed, Integer.class)) {
                return 3; // char → int, comme en Java
            }
            if (boxed == String.class || boxed == CharSequence.class) {
                return 5;
            }
            return NO_MATCH;
        }
        if (value instanceof Number n && isNumericType(boxed)) {
            return widening(n, boxed);
        }
        if (value instanceof String s && boxed == Character.class && s.length() == 1) {
            return 4;
        }
        return NO_MATCH;
    }

    /** Élargissement numérique permis par Java (int → long → double), ou NO_MATCH. */
    private static int widening(Number n, Class<?> target) {
        Class<?> source = n.getClass();
        return isWideningTarget(target, source) ? 2 : NO_MATCH;
    }

    private static final List<Class<?>> NUMERIC_ORDER = List.of(Byte.class, Short.class, Integer.class, Long.class,
            Float.class, Double.class);

    private static boolean isWideningTarget(Class<?> target, Class<?> source) {
        int s = NUMERIC_ORDER.indexOf(source);
        int t = NUMERIC_ORDER.indexOf(target);
        return s >= 0 && t >= s;
    }

    private static boolean isNumericType(Class<?> type) {
        return NUMERIC_ORDER.contains(type);
    }

    private static Object convert(Class<?> type, Object value) {
        if (value == null) {
            return null;
        }
        Class<?> boxed = boxed(type);
        if (boxed.isInstance(value)) {
            return value;
        }
        if (value instanceof Character c) {
            return boxed == String.class || boxed == CharSequence.class ? String.valueOf(c) : convertNumber(boxed, (int) c);
        }
        if (value instanceof String s && boxed == Character.class) {
            return s.charAt(0);
        }
        return convertNumber(boxed, (Number) value);
    }

    private static Object convertNumber(Class<?> boxed, Number n) {
        if (boxed == Long.class) {
            return n.longValue();
        }
        if (boxed == Integer.class) {
            return n.intValue();
        }
        if (boxed == Short.class) {
            return n.shortValue();
        }
        if (boxed == Float.class) {
            return n.floatValue();
        }
        if (boxed == Double.class) {
            return n.doubleValue();
        }
        return n;
    }

    private static Class<?> boxed(Class<?> type) {
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

    private static String signature(Method m) {
        List<String> params = new ArrayList<>();
        for (Class<?> p : m.getParameterTypes()) {
            params.add(p.getSimpleName());
        }
        return m.getName() + "(" + String.join(", ", params) + ")";
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
