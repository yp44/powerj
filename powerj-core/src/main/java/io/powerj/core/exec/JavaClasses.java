package io.powerj.core.exec;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java classes accessible from the shell (specification FR-46, FR-47, FR-52): public classes and interfaces
 * of the packages exported by the runtime's {@code java.*} modules, by their fully qualified name or, thanks
 * to imports, by their simple name. Classes of third-party modules (cmdlets, JLine, PowerJ) are not
 * exposed.
 */
public final class JavaClasses {

    /** Packages imported by default (FR-47): no duplicate class name among them. */
    public static final List<String> DEFAULT_IMPORTS = List.of(
            "java.lang", "java.math", "java.text",
            "java.util", "java.util.function", "java.util.stream", "java.util.regex", "java.util.concurrent",
            "java.io", "java.nio.file", "java.nio.charset",
            "java.net", "java.net.http",
            "java.time", "java.time.format");

    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long",
            "float", "double", "void");

    /** Result of looking up a simple name. */
    private sealed interface Lookup {
        record Found(Class<?> type) implements Lookup { }

        record Missing() implements Lookup { }

        record Ambiguous(List<String> candidates) implements Lookup { }
    }

    /**
     * The imports in force, immutable, with the simple names resolved through them. An import creates a new one, so a
     * name resolved with the old imports can never be seen with the new ones, and nothing is computed or cleared while
     * holding a lock (review C-14: the lookup held the lock of a map bin and waited for the monitor of the object,
     * while {@code addImport} held the monitor and cleared the map).
     */
    private record Imports(Set<String> packages, Map<String, String> classes, Map<String, Lookup> simpleNames) {

        Imports {
            packages = Collections.unmodifiableSet(packages);
            classes = Collections.unmodifiableMap(classes);
        }
    }

    private volatile Imports imports = new Imports(new LinkedHashSet<>(DEFAULT_IMPORTS), new LinkedHashMap<>(),
            new ConcurrentHashMap<>());
    private final Map<String, Optional<Class<?>>> qualifiedNames = new ConcurrentHashMap<>();

    /** Active imports, in order: packages ({@code java.util.*}) then classes. */
    public List<String> imports() {
        Imports current = imports;
        List<String> all = new ArrayList<>();
        current.packages().forEach(p -> all.add(p + ".*"));
        all.addAll(current.classes().values());
        return all;
    }

    /**
     * {@code import java.security.*} or {@code import javax.crypto.Cipher}.
     *
     * @throws PjException if the package or class does not exist in the Java library
     */
    public synchronized void addImport(String target) { // synchronized: one writer at a time; readers take no lock
        Set<String> packages = new LinkedHashSet<>(imports.packages());
        Map<String, String> classes = new LinkedHashMap<>(imports.classes());
        if (target.endsWith(".*")) {
            String pkg = target.substring(0, target.length() - 2);
            if (!isExportedPackage(pkg)) {
                throw new PjException(Messages.get("import.packageNotFound", pkg));
            }
            packages.add(pkg);
        } else {
            Class<?> type = qualified(target).orElseThrow(() ->
                    new PjException(Messages.get("import.classNotFound", target)));
            classes.put(type.getSimpleName(), target);
        }
        imports = new Imports(packages, classes, new ConcurrentHashMap<>());
    }

    /** Class designated by a simple ({@code List}) or fully qualified name ({@code java.util.List}, {@code HttpResponse.BodyHandlers}). */
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

    /** Like {@link #find}, with an explicit error message (ambiguity between imports, unknown class). */
    public Class<?> require(String name) {
        if (!name.contains(".") && simple(name) instanceof Lookup.Ambiguous(var candidates)) {
            throw new PjException(Messages.get("java.ambiguousName", name, String.join(", ", candidates)));
        }
        return find(name).orElseThrow(() -> new PjException(Messages.get("java.classNotFound", name)));
    }

    /** Class designated by a simple name through the imports, if it exists and is not ambiguous. */
    Optional<Class<?>> simpleClass(String name) {
        return simple(name) instanceof Lookup.Found(var type) ? Optional.of(type) : Optional.empty();
    }

    /** Throws the ambiguity error if {@code name} matches several imports. */
    void checkAmbiguity(String name) {
        if (simple(name) instanceof Lookup.Ambiguous(var candidates)) {
            throw new PjException(Messages.get("java.ambiguousName", name, String.join(", ", candidates)));
        }
    }

    /** Public nested class {@code outer.name} ({@code HttpResponse.BodyHandlers}). */
    static Optional<Class<?>> nested(Class<?> outer, String name) {
        for (Class<?> inner : outer.getClasses()) {
            if (inner.getSimpleName().equals(name) && accessible(inner)) {
                return Optional.of(inner);
            }
        }
        return Optional.empty();
    }

    /**
     * Qualified name designating a class or a static field ({@code java.lang.Math.PI},
     * {@code DayOfWeek.MONDAY}): at the start of a line, it is a Java expression and not a command (FR-46).
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

    /** Public static field of an accessible class. */
    static Optional<Field> staticField(Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            return Modifier.isStatic(field.getModifiers()) && accessible(field.getDeclaringClass())
                    ? Optional.of(field) : Optional.empty();
        } catch (NoSuchFieldException _) {
            return Optional.empty();
        }
    }

    /** Public class of a package exported by a {@code java.*} module (FR-52). */
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
        Imports current = imports; // read once: the imports may change while the name is resolved
        Lookup known = current.simpleNames().get(name);
        if (known != null) {
            return known;
        }
        // Computed outside the map: two threads may both do it, and they get the same answer.
        Lookup computed = lookupSimple(current, name);
        Lookup previous = current.simpleNames().putIfAbsent(name, computed);
        return previous != null ? previous : computed;
    }

    private Lookup lookupSimple(Imports current, String name) {
        String imported = current.classes().get(name);
        if (imported != null) {
            return qualified(imported).<Lookup>map(Lookup.Found::new).orElse(new Lookup.Missing());
        }
        List<Class<?>> found = new ArrayList<>();
        for (String pkg : current.packages()) {
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
                return Optional.empty(); // a package, not a class: avoids useless lookups
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
