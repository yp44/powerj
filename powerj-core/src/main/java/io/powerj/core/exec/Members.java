package io.powerj.core.exec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Membres publics accessibles d'un type : composants de record, getters, champs, méthodes. Seuls les
 * types publics des packages exportés sont considérés ; une classe interne du JDK (celle de
 * {@code Path.of(…)} par exemple) est vue à travers ses interfaces publiques.
 */
public final class Members {

    /** Membre affiché par {@code help members} (spécification FR-29). */
    public record Member(String name, String kind, String type) { }

    private static final ClassValue<List<Class<?>>> ACCESSIBLE_TYPES = new ClassValue<>() {
        @Override
        protected List<Class<?>> computeValue(Class<?> type) {
            return computeAccessibleTypes(type);
        }
    };

    private Members() {
    }

    /** Type lui-même, superclasses et interfaces, en ne gardant que les types publics et exportés. */
    static List<Class<?>> accessibleTypes(Class<?> type) {
        return ACCESSIBLE_TYPES.get(type);
    }

    private static List<Class<?>> computeAccessibleTypes(Class<?> type) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> t = queue.poll();
            if (!seen.add(t)) {
                continue;
            }
            if (t.getSuperclass() != null) {
                queue.add(t.getSuperclass());
            }
            queue.addAll(List.of(t.getInterfaces()));
        }
        return seen.stream().filter(Members::isAccessible).toList();
    }

    static boolean isAccessible(Class<?> type) {
        return Modifier.isPublic(type.getModifiers()) && type.getModule().isExported(type.getPackageName());
    }

    /** Getter accessible {@code getNom()} / {@code isNom()} pour la propriété {@code name}. */
    static Optional<Method> getter(Class<?> type, String name) {
        for (Class<?> t : accessibleTypes(type)) {
            for (Method m : t.getMethods()) {
                if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())
                        && isAccessible(m.getDeclaringClass()) && isGetterFor(m, name)) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isGetterFor(Method method, String name) {
        String m = method.getName();
        return m.equalsIgnoreCase("get" + name)
                || (m.equalsIgnoreCase("is" + name)
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class));
    }

    /** Champ public d'instance, si le type est accessible. */
    static Optional<Field> field(Class<?> type, String name) {
        for (Class<?> t : accessibleTypes(type)) {
            for (Field f : t.getFields()) {
                if (f.getName().equalsIgnoreCase(name) && !Modifier.isStatic(f.getModifiers())) {
                    return Optional.of(f);
                }
            }
        }
        return Optional.empty();
    }

    /** Membres d'une valeur : propriétés (composants, getters, champs) puis méthodes. */
    public static List<Member> of(Object value) {
        Class<?> type = value.getClass();
        Map<String, Member> properties = new LinkedHashMap<>();
        if (type.isRecord()) {
            for (RecordComponent c : type.getRecordComponents()) {
                properties.put(c.getName().toLowerCase(Locale.ROOT),
                        new Member(c.getName(), "propriété", simple(c.getGenericType().getTypeName())));
            }
        }
        List<Member> methods = new ArrayList<>();
        Set<String> seenMethods = new LinkedHashSet<>();
        for (Class<?> t : accessibleTypes(type)) {
            for (Method m : t.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class
                        || !isAccessible(m.getDeclaringClass())
                        || m.getName().equals("equals") || m.getName().equals("hashCode")) {
                    continue;
                }
                String property = propertyName(m);
                if (property != null) {
                    properties.putIfAbsent(property.toLowerCase(Locale.ROOT),
                            new Member(property, "propriété", simple(m.getGenericReturnType().getTypeName())));
                }
                String signature = m.getName() + "(" + String.join(", ",
                        java.util.Arrays.stream(m.getGenericParameterTypes()).map(p -> simple(p.getTypeName())).toList()) + ")";
                if (seenMethods.add(signature)) {
                    methods.add(new Member(signature, "méthode", simple(m.getGenericReturnType().getTypeName())));
                }
            }
            for (Field f : t.getFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    properties.putIfAbsent(f.getName().toLowerCase(Locale.ROOT),
                            new Member(f.getName(), "champ", simple(f.getGenericType().getTypeName())));
                }
            }
        }
        List<Member> all = new ArrayList<>(properties.values());
        methods.sort(java.util.Comparator.comparing(Member::name));
        all.addAll(methods);
        return all;
    }

    /** {@code getParent} → {@code parent}, {@code isDirectory} → {@code directory}. */
    private static String propertyName(Method m) {
        if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
            return null;
        }
        String name = m.getName();
        String rest = name.startsWith("get") && name.length() > 3 ? name.substring(3)
                : name.startsWith("is") && name.length() > 2
                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) ? name.substring(2)
                : null;
        if (rest == null || !Character.isUpperCase(rest.charAt(0))) {
            return null;
        }
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    private static String simple(String typeName) {
        return typeName.replaceAll("\\b(?:[a-z][a-z0-9_]*\\.)+([A-Z])", "$1");
    }
}
