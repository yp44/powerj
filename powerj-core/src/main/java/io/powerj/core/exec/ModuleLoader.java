package io.powerj.core.exec;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolutionException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletProvider;

/**
 * Loading of third-party modules (specification §4.4): each jar in {@code ~/.powerj/modules}, or each
 * subdirectory (a module and its dependencies), is loaded into its own {@link ModuleLayer}, which isolates
 * the modules' dependencies from one another. Cmdlets are discovered by {@link ServiceLoader}.
 * <p>
 * The modules' packages are opened to PowerJ only (reading records, options, display): they
 * do not become usable in Java expressions (§3.13).
 */
public final class ModuleLoader {

    /** Result of a load: names of the cmdlets added, warnings. */
    public record Result(List<String> cmdlets, List<String> warnings) {

        static Result failure(String warning) {
            return new Result(List.of(), List.of(warning));
        }
    }

    private final CmdletRegistry registry;
    private final Set<String> reserved;

    /**
     * @param reserved names of the built-in commands: a cmdlet with the same name would never be called
     */
    public ModuleLoader(CmdletRegistry registry, Set<String> reserved) {
        this.registry = registry;
        this.reserved = reserved;
    }

    /** Loads each jar and each subdirectory of {@code dir}; a missing directory loads nothing. */
    public List<Result> loadAll(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        Set<Path> entries = new TreeSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry) || isJar(entry)) {
                    entries.add(entry);
                }
            }
        } catch (IOException e) {
            return List.of(Result.failure("modules : lecture impossible de " + dir + " (" + e.getMessage() + ")"));
        }
        return entries.stream().map(this::load).toList();
    }

    /** Loads a jar, or a directory containing a module and its dependencies ({@code mod-load}). */
    public Result load(Path source) {
        Path path = source.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            return Result.failure("module introuvable : " + path);
        }
        if (!Files.isDirectory(path) && !isJar(path)) {
            return Result.failure(path.getFileName() + " : un module est un fichier .jar (ou un dossier de jars)");
        }
        try {
            return define(path);
        } catch (FindException | ResolutionException | LayerInstantiationException e) {
            return Result.failure(path.getFileName() + " : module invalide (" + e.getMessage() + ")");
        } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
            return Result.failure(path.getFileName() + " : erreur au chargement (" + e + ")");
        }
    }

    private Result define(Path path) {
        ModuleFinder finder = ModuleFinder.of(path);
        ModuleLayer parent = parentLayer();
        List<String> roots = new ArrayList<>();
        for (ModuleReference reference : finder.findAll()) {
            String name = reference.descriptor().name();
            if (parent.findModule(name).isEmpty()) {
                roots.add(name);
            }
        }
        if (roots.isEmpty()) {
            return Result.failure(path.getFileName() + " : aucun module nouveau (déjà fourni par PowerJ, ou dossier vide)");
        }
        for (String root : roots) {
            if (registry.hasModule(root)) {
                return Result.failure(path.getFileName() + " : module " + root + " déjà chargé");
            }
        }
        // Modules du runtime et de PowerJ d'abord : un jar qui embarque sa propre copie de powerj-api
        // utilise celle du shell.
        Configuration configuration = Configuration.resolve(ModuleFinder.of(), List.of(parent.configuration()),
                finder, roots);
        var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(parent),
                ClassLoader.getSystemClassLoader());
        ModuleLayer layer = controller.layer();
        Module powerj = ModuleLoader.class.getModule();
        for (Module module : layer.modules()) {
            if (!module.getDescriptor().isAutomatic()) {
                for (String pkg : module.getPackages()) {
                    controller.addOpens(module, pkg, powerj);
                }
            }
        }
        List<String> cmdlets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean found = false;
        for (CmdletProvider provider : ServiceLoader.load(layer, CmdletProvider.class)) {
            Module module = provider.getClass().getModule();
            if (module.getLayer() != layer) {
                continue; // fournisseur d'un module parent (cmdlets intégrés)
            }
            found = true;
            List<Cmdlet<?, ?, ?>> provided = new ArrayList<>();
            for (Cmdlet<?, ?, ?> cmdlet : provider.cmdlets()) {
                var info = cmdlet.getClass().getAnnotation(io.powerj.api.CmdletInfo.class);
                if (info != null && reserved.contains(info.name())) {
                    warnings.add("cmdlet « " + info.name() + " » du module " + module.getName()
                            + " ignoré : nom réservé à une commande interne");
                } else {
                    provided.add(cmdlet);
                }
            }
            warnings.addAll(registry.addModule(module, Optional.of(path), provided));
        }
        for (var loaded : registry.modules()) {
            if (layer.findModule(loaded.name()).filter(m -> m.getLayer() == layer).isPresent()) {
                cmdlets.addAll(loaded.cmdlets());
            }
        }
        if (!found) {
            warnings.add(path.getFileName() + " : aucun cmdlet (le module doit déclarer « provides "
                    + CmdletProvider.class.getName() + " with … »)");
        }
        return new Result(List.copyOf(cmdlets), List.copyOf(warnings));
    }

    /** Layer containing {@code powerj-api}; the boot layer during tests (classpath). */
    private static ModuleLayer parentLayer() {
        ModuleLayer layer = CmdletProvider.class.getModule().getLayer();
        return layer != null ? layer : ModuleLayer.boot();
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }
}
