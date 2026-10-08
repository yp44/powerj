package io.powerj.core.exec;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Index of the exported packages and public classes of the runtime's {@code java.*} modules, for Java
 * completion (specification FR-24b). The classes of a package are listed on first request and then
 * cached; {@link #warmUp} prepares the imported packages in the background.
 */
public final class JavaIndex {

    private static final Logger LOG = Logger.getLogger(JavaIndex.class.getName());

    /** Exported package → module that contains it. */
    private final Map<String, String> packages = new ConcurrentHashMap<>();
    private final Map<String, List<String>> classes = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    /** All packages exported by the {@code java.*} modules, sorted. */
    public List<String> packages() {
        load();
        return new ArrayList<>(new TreeSet<>(packages.keySet()));
    }

    /** Simple names of the public top-level classes of an exported package, sorted. */
    public List<String> classes(String pkg) {
        load();
        String module = packages.get(pkg);
        if (module == null) {
            return List.of();
        }
        return classes.computeIfAbsent(pkg, p -> listClasses(module, p));
    }

    /** Prepares the index and the classes of the given packages, without blocking the caller. */
    public void warmUp(List<String> packagesToLoad) {
        Thread.ofVirtual().name("powerj-index-java").start(() -> {
            try {
                packagesToLoad.forEach(this::classes);
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Index Java incomplet", e);
            }
        });
    }

    private void load() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            for (Module module : ModuleLayer.boot().modules()) {
                if (!module.getName().startsWith("java.")) {
                    continue;
                }
                for (String pkg : module.getPackages()) {
                    if (module.isExported(pkg)) {
                        packages.put(pkg, module.getName());
                    }
                }
            }
            loaded = true;
        }
    }

    private static List<String> listClasses(String moduleName, String pkg) {
        Optional<ModuleReference> reference = ModuleFinder.ofSystem().find(moduleName);
        if (reference.isEmpty()) {
            return List.of();
        }
        String prefix = pkg.replace('.', '/') + "/";
        TreeSet<String> names = new TreeSet<>();
        try (ModuleReader reader = reference.get().open(); Stream<String> resources = reader.list()) {
            resources.filter(r -> r.startsWith(prefix) && r.endsWith(".class") && r.indexOf('/', prefix.length()) < 0)
                    .map(r -> r.substring(prefix.length(), r.length() - ".class".length()))
                    .filter(n -> !n.contains("$") && !n.equals("module-info") && !n.equals("package-info"))
                    .filter(n -> isPublic(pkg + "." + n))
                    .forEach(names::add);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Lecture du module " + moduleName + " impossible", e);
        }
        return List.copyOf(names);
    }

    private static boolean isPublic(String className) {
        try {
            return JavaClasses.accessible(Class.forName(className, false, ClassLoader.getPlatformClassLoader()));
        } catch (ClassNotFoundException | LinkageError _) {
            return false;
        }
    }
}
