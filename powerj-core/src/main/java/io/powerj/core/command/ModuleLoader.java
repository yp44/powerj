package io.powerj.core.command;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolutionException;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletProvider;
import io.powerj.core.i18n.Messages;

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

    /** Manifest attribute of a module that uses native code: {@code Enable-Native-Access: true}. */
    static final String NATIVE_ACCESS_ATTRIBUTE = "Enable-Native-Access";

    private final CmdletRegistry registry;
    private final Set<String> reserved;
    private volatile NativeAccessPolicy nativeAccess = NativeAccessPolicy.NONE;

    /**
     * @param reserved names of the built-in commands: a cmdlet with the same name would never be called
     */
    public ModuleLoader(CmdletRegistry registry, Set<String> reserved) {
        this.registry = registry;
        this.reserved = reserved;
    }

    /** Policy asked before loading a module that declares {@value #NATIVE_ACCESS_ATTRIBUTE} (none by default). */
    public void nativeAccess(NativeAccessPolicy policy) {
        this.nativeAccess = Objects.requireNonNull(policy, "policy");
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
            return List.of(Result.failure(Messages.get("module.dirUnreadable", dir, e.getMessage())));
        }
        return entries.stream().map(this::load).toList();
    }

    /** Loads a jar, or a directory containing a module and its dependencies ({@code mod-load}). */
    public Result load(Path source) {
        Path path = source.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            return Result.failure(Messages.get("module.notFound", path));
        }
        if (!Files.isDirectory(path) && !isJar(path)) {
            return Result.failure(Messages.get("module.notJar", path.getFileName()));
        }
        try {
            return define(path);
        } catch (FindException | ResolutionException | LayerInstantiationException e) {
            return Result.failure(Messages.get("module.invalid", path.getFileName(), e.getMessage()));
        } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
            return Result.failure(Messages.get("module.loadError", path.getFileName(), e));
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
            return Result.failure(Messages.get("module.nothingNew", path.getFileName()));
        }
        for (String root : roots) {
            if (registry.hasModule(root)) {
                return Result.failure(Messages.get("module.alreadyLoaded", path.getFileName(), root));
            }
        }
        // Runtime and PowerJ modules first: a jar that bundles its own copy of powerj-api
        // uses the shell's one.
        Configuration configuration = Configuration.resolve(ModuleFinder.of(), List.of(parent.configuration()),
                finder, roots);
        boolean grantNativeAccess = false;
        List<String> declaring = declaringNativeAccess(finder, roots);
        if (!declaring.isEmpty()) {
            List<String> inLayer = configuration.modules().stream().map(ResolvedModule::name).sorted().toList();
            String fingerprint;
            try {
                fingerprint = ContentFingerprint.of(locations(configuration));
            } catch (IOException e) {
                return Result.failure(Messages.get("module.nativeAccess.unreadable", path.getFileName(),
                        e.getMessage()));
            }
            for (String module : declaring) {
                Optional<Boolean> answer = nativeAccess.consent(module, inLayer, fingerprint);
                if (answer.isEmpty()) {
                    return Result.failure(Messages.get("module.nativeAccess.noAnswer", path.getFileName(), module,
                            NativeAccessPolicy.setting(module), NativeAccessPolicy.allowed(fingerprint)));
                }
                if (!answer.get()) {
                    return Result.failure(Messages.get("module.nativeAccess.refused", path.getFileName(), module,
                            NativeAccessPolicy.setting(module)));
                }
            }
            grantNativeAccess = true;
        }
        var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(parent),
                ClassLoader.getSystemClassLoader());
        ModuleLayer layer = controller.layer();
        if (grantNativeAccess) {
            // The code that loads the native libraries is often a dependency (javafx.graphics), not the module itself.
            layer.modules().forEach(controller::enableNativeAccess);
        }
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
                continue; // provider from a parent module (built-in cmdlets)
            }
            found = true;
            List<Cmdlet<?, ?, ?>> provided = new ArrayList<>();
            for (Cmdlet<?, ?, ?> cmdlet : provider.cmdlets()) {
                var info = cmdlet.getClass().getAnnotation(io.powerj.api.CmdletInfo.class);
                if (info != null && reserved.contains(info.name())) {
                    warnings.add(Messages.get("module.reservedName", info.name(), module.getName()));
                } else {
                    provided.add(cmdlet);
                }
            }
            warnings.addAll(registry.addModule(module, Optional.of(path), provider, provided));
        }
        for (var loaded : registry.modules()) {
            if (layer.findModule(loaded.name()).filter(m -> m.getLayer() == layer).isPresent()) {
                cmdlets.addAll(loaded.cmdlets());
            }
        }
        if (!found) {
            warnings.add(Messages.get("module.noCmdlet", path.getFileName(), CmdletProvider.class.getName()));
        }
        return new Result(List.copyOf(cmdlets), List.copyOf(warnings));
    }

    /** Where each module of the layer comes from, by name: the jar or the folder that the user would be trusting. */
    private static Map<String, Path> locations(Configuration configuration) throws IOException {
        Map<String, Path> locations = new TreeMap<>();
        for (ResolvedModule module : configuration.modules()) {
            URI location = module.reference().location()
                    .orElseThrow(() -> new IOException(module.name() + " has no location"));
            locations.put(module.name(), Path.of(location));
        }
        return locations;
    }

    /** Names of the modules among {@code roots} whose manifest has {@code Enable-Native-Access: true}. */
    private static List<String> declaringNativeAccess(ModuleFinder finder, List<String> roots) {
        List<String> declaring = new ArrayList<>();
        for (ModuleReference reference : finder.findAll()) {
            String name = reference.descriptor().name();
            if (roots.contains(name) && reference.location().map(ModuleLoader::declaresNativeAccess).orElse(false)) {
                declaring.add(name);
            }
        }
        declaring.sort(null);
        return declaring;
    }

    /** {@code true} if the jar at {@code location} has {@code Enable-Native-Access: true} in its manifest. */
    private static boolean declaresNativeAccess(URI location) {
        try (JarFile jar = new JarFile(Path.of(location).toFile())) {
            Manifest manifest = jar.getManifest();
            return manifest != null
                    && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue(NATIVE_ACCESS_ATTRIBUTE));
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    /** Layer containing {@code powerj-api}; the boot layer during tests (classpath). */
    private static ModuleLayer parentLayer() {
        ModuleLayer layer = CmdletProvider.class.getModule().getLayer();
        return layer != null ? layer : ModuleLayer.boot();
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }
}
