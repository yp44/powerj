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
 * Index des packages exportés et des classes publiques des modules {@code java.*} du runtime, pour la
 * complétion Java (spécification FR-24b). Les classes d'un package sont listées à la première demande puis
 * mises en cache ; {@link #warmUp} prépare les packages importés en tâche de fond.
 */
public final class JavaIndex {

    private static final Logger LOG = Logger.getLogger(JavaIndex.class.getName());

    /** Package exporté → module qui le contient. */
    private final Map<String, String> packages = new ConcurrentHashMap<>();
    private final Map<String, List<String>> classes = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    /** Tous les packages exportés par les modules {@code java.*}, triés. */
    public List<String> packages() {
        load();
        return new ArrayList<>(new TreeSet<>(packages.keySet()));
    }

    /** Noms simples des classes publiques de premier niveau d'un package exporté, triés. */
    public List<String> classes(String pkg) {
        load();
        String module = packages.get(pkg);
        if (module == null) {
            return List.of();
        }
        return classes.computeIfAbsent(pkg, p -> listClasses(module, p));
    }

    /** Prépare l'index et les classes des packages donnés, sans bloquer l'appelant. */
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
