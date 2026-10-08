package io.powerj.core.exec;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classes Java accessibles depuis le shell (spécification FR-46, FR-47, FR-52) : classes et interfaces
 * publiques des packages exportés par les modules {@code java.*} du runtime, par leur nom complet ou, grâce
 * aux imports, par leur nom simple. Les classes des modules tiers (cmdlets, JLine, PowerJ) ne sont pas
 * exposées.
 */
public final class JavaClasses {

    /** Packages importés par défaut (FR-47) : aucun nom de classe en double entre eux. */
    public static final List<String> DEFAULT_IMPORTS = List.of(
            "java.lang", "java.math", "java.text",
            "java.util", "java.util.function", "java.util.stream", "java.util.regex", "java.util.concurrent",
            "java.io", "java.nio.file", "java.nio.charset",
            "java.net", "java.net.http",
            "java.time", "java.time.format");

    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long",
            "float", "double", "void");

    /** Résultat de recherche d'un nom simple. */
    private sealed interface Lookup {
        record Found(Class<?> type) implements Lookup { }

        record Missing() implements Lookup { }

        record Ambiguous(List<String> candidates) implements Lookup { }
    }

    private final Set<String> packages = new LinkedHashSet<>(DEFAULT_IMPORTS);
    /** Imports de classes : nom simple → nom complet. */
    private final Map<String, String> classImports = new ConcurrentHashMap<>();
    private final Map<String, Lookup> simpleNames = new ConcurrentHashMap<>();
    private final Map<String, Optional<Class<?>>> qualifiedNames = new ConcurrentHashMap<>();

    /** Imports actifs, dans l'ordre : packages ({@code java.util.*}) puis classes. */
    public synchronized List<String> imports() {
        List<String> all = new ArrayList<>();
        packages.forEach(p -> all.add(p + ".*"));
        all.addAll(classImports.values());
        return all;
    }

    /**
     * {@code import java.security.*} ou {@code import javax.crypto.Cipher}.
     *
     * @throws PjException si le package ou la classe n'existe pas dans la bibliothèque Java
     */
    public synchronized void addImport(String target) {
        if (target.endsWith(".*")) {
            String pkg = target.substring(0, target.length() - 2);
            if (!isExportedPackage(pkg)) {
                throw new PjException("import : package introuvable dans la bibliothèque Java : " + pkg);
            }
            packages.add(pkg);
        } else {
            Class<?> type = qualified(target).orElseThrow(() ->
                    new PjException("import : classe introuvable dans la bibliothèque Java : " + target));
            classImports.put(type.getSimpleName(), target);
        }
        simpleNames.clear();
    }

    /** Classe désignée par un nom simple ({@code List}) ou complet ({@code java.util.List}, {@code HttpResponse.BodyHandlers}). */
    public Optional<Class<?>> find(String name) {
        if (PRIMITIVES.contains(name)) {
            return Optional.of(primitive(name));
        }
        String[] parts = name.split("\\.");
        Optional<Class<?>> type = Optional.empty();
        int next = 0;
        if (simple(parts[0]) instanceof Lookup.Found(var found)) {
            type = Optional.of(found);
            next = 1;
        } else {
            var prefix = new StringBuilder();
            for (int i = 0; i < parts.length && type.isEmpty(); i++) {
                prefix.append(i == 0 ? "" : ".").append(parts[i]);
                type = qualified(prefix.toString());
                next = i + 1;
            }
        }
        for (int i = next; i < parts.length && type.isPresent(); i++) {
            type = nested(type.get(), parts[i]);
        }
        return type;
    }

    /** Comme {@link #find}, avec un message d'erreur explicite (ambiguïté entre imports, classe inconnue). */
    public Class<?> require(String name) {
        if (!name.contains(".") && simple(name) instanceof Lookup.Ambiguous(var candidates)) {
            throw new PjException("nom ambigu : " + name + " (" + String.join(", ", candidates)
                    + ") ; préciser le nom complet");
        }
        return find(name).orElseThrow(() -> new PjException("classe introuvable : " + name));
    }

    /** Classe désignée par un nom simple grâce aux imports, si elle existe et n'est pas ambiguë. */
    Optional<Class<?>> simpleClass(String name) {
        return simple(name) instanceof Lookup.Found(var type) ? Optional.of(type) : Optional.empty();
    }

    /** Lève l'erreur d'ambiguïté si {@code name} correspond à plusieurs imports. */
    void checkAmbiguity(String name) {
        if (simple(name) instanceof Lookup.Ambiguous(var candidates)) {
            throw new PjException("nom ambigu : " + name + " (" + String.join(", ", candidates)
                    + ") ; préciser le nom complet");
        }
    }

    /** Classe publique imbriquée {@code outer.name} ({@code HttpResponse.BodyHandlers}). */
    static Optional<Class<?>> nested(Class<?> outer, String name) {
        for (Class<?> inner : outer.getClasses()) {
            if (inner.getSimpleName().equals(name) && accessible(inner)) {
                return Optional.of(inner);
            }
        }
        return Optional.empty();
    }

    /**
     * Nom qualifié désignant une classe ou un champ statique ({@code java.lang.Math.PI},
     * {@code DayOfWeek.MONDAY}) : en tête de ligne, c'est une expression Java et non une commande (FR-46).
     */
    public boolean isStaticReference(String name) {
        if (find(name).isPresent()) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        Optional<Class<?>> owner = find(name.substring(0, dot));
        return owner.isPresent() && staticField(owner.get(), name.substring(dot + 1)).isPresent();
    }

    /** Champ statique public d'une classe accessible. */
    static Optional<Field> staticField(Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            return Modifier.isStatic(field.getModifiers()) && accessible(field.getDeclaringClass())
                    ? Optional.of(field) : Optional.empty();
        } catch (NoSuchFieldException _) {
            return Optional.empty();
        }
    }

    /** Classe publique d'un package exporté d'un module {@code java.*} (FR-52). */
    static boolean accessible(Class<?> type) {
        if (type.isArray()) {
            return accessible(type.componentType());
        }
        if (type.isPrimitive()) {
            return true;
        }
        Module module = type.getModule();
        for (Class<?> t = type; t != null; t = t.getEnclosingClass()) {
            if (!Modifier.isPublic(t.getModifiers())) {
                return false;
            }
        }
        return module.isNamed() && module.getName().startsWith("java.") && module.isExported(type.getPackageName());
    }

    private Lookup simple(String name) {
        return simpleNames.computeIfAbsent(name, this::lookupSimple);
    }

    private Lookup lookupSimple(String name) {
        String imported = classImports.get(name);
        if (imported != null) {
            return qualified(imported).<Lookup>map(Lookup.Found::new).orElse(new Lookup.Missing());
        }
        List<Class<?>> found = new ArrayList<>();
        List<String> searched;
        synchronized (this) {
            searched = List.copyOf(packages);
        }
        for (String pkg : searched) {
            qualified(pkg + "." + name).ifPresent(found::add);
        }
        return switch (found.size()) {
            case 0 -> new Lookup.Missing();
            case 1 -> new Lookup.Found(found.getFirst());
            default -> new Lookup.Ambiguous(found.stream().map(Class::getName).toList());
        };
    }

    private Optional<Class<?>> qualified(String name) {
        return qualifiedNames.computeIfAbsent(name, n -> {
            if (!Character.isUpperCase(n.charAt(n.lastIndexOf('.') + 1))) {
                return Optional.empty(); // un package, pas une classe : évite des recherches inutiles
            }
            try {
                Class<?> type = Class.forName(n, false, ClassLoader.getPlatformClassLoader());
                return accessible(type) ? Optional.of(type) : Optional.empty();
            } catch (ClassNotFoundException | LinkageError _) {
                return Optional.empty();
            }
        });
    }

    private static boolean isExportedPackage(String pkg) {
        for (Module module : ModuleLayer.boot().modules()) {
            if (module.getName().startsWith("java.") && module.getPackages().contains(pkg) && module.isExported(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> primitive(String name) {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "char" -> char.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> void.class;
        };
    }
}
