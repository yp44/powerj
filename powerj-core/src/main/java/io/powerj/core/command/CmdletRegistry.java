package io.powerj.core.command;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Logger;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.CmdletProvider;
import io.powerj.api.ColumnProvider;
import io.powerj.api.Language;
import io.powerj.api.Previewable;
import io.powerj.core.i18n.Messages;

/**
 * Available cmdlets: those of the startup modules, discovered by {@link ServiceLoader}, then those of the
 * third-party modules loaded by {@link ModuleLoader} (specification §4). Modifiable at runtime ({@code mod-load}):
 * the methods are synchronized.
 */
public final class CmdletRegistry {

    private static final Logger LOG = Logger.getLogger(CmdletRegistry.class.getName());

    /** Registered cmdlet with its metadata. */
    public record Registered(Cmdlet<?, ?, ?> cmdlet, CmdletInfo info, Class<? extends Record> parameters,
                             Class<?> input, Class<?> output, String module, CmdletProvider provider,
                             String prefix) {

        public String name() {
            return info.name();
        }

        /**
         * Preview mode (FR-66): may the cmdlet run in a preview with these parameters? Asks the {@link Previewable}
         * capability; a cmdlet that does not have it can.
         */
        @SuppressWarnings("unchecked")
        public boolean previewable(Record params) {
            return !(cmdlet instanceof Previewable<?> capable) || ((Previewable<Record>) capable).canPreview(params);
        }

        /**
         * Columns that the rows of the cmdlet will have, read from the text of its arguments: the {@link ColumnProvider}
         * capability. Empty when the cmdlet has none, or knows none.
         */
        public List<String> columns(String arguments) {
            return cmdlet instanceof ColumnProvider provider ? provider.columnsOf(arguments) : List.of();
        }

        /** Summary in the current language ({@code <name>.summary} of the provider's messages, FR-61). */
        public String summary() {
            return translated(name() + ".summary", info.summary());
        }

        /** Category in the current language ({@code category.<Category>} of the provider's messages). */
        public String category() {
            return translated("category." + info.category(), info.category());
        }

        /** Description of an option in the current language ({@code <name>.option.<longName>}). */
        public String optionDescription(String longName, String annotationText) {
            return translated(name() + ".option." + longName, annotationText);
        }

        private String translated(String key, String fallback) {
            if (provider == null) {
                return fallback;
            }
            try {
                ResourceBundle bundle = provider.messages(Language.current());
                return bundle != null && bundle.containsKey(key) ? bundle.getString(key) : fallback;
            } catch (RuntimeException e) {
                return fallback; // module translations are optional: never break help or completion
            }
        }

        /** {@code true} if the cmdlet reads objects from the pipeline ({@code I} other than {@code Void}). */
        public boolean readsInput() {
            return input != Void.class;
        }

        /** {@code true} if the cmdlet declares that it may run in preview mode (FR-66). */
        public boolean preview() {
            return info.preview();
        }

        /** {@code true} if the cmdlet takes the short form {@code property operator value} (FR-36). */
        public boolean comparison() {
            return info.comparison();
        }

        /** {@code true} if the cmdlet emits the objects it receives, unchanged. */
        public boolean passThrough() {
            return info.passThrough();
        }

        /**
         * Qualified name, always usable: {@code greet:greet} (FR-17). The prefix is the last segment of the module name
         * ({@code com.example.greet} gives {@code greet}), or the whole name when another module already has that segment
         * ({@code org.other.greet:greet}): two modules never share a qualified name (audit v2, R-06).
         */
        public String qualifiedName() {
            return prefix + ":" + name();
        }

        private Registered withPrefix(String chosen) {
            return new Registered(cmdlet, info, parameters, input, output, module, provider, chosen);
        }
    }

    /**
     * Loaded module (a {@code mod-list} row).
     *
     * @param source originating jar or directory; {@code null} (displayed {@code (built-in)}) for the modules
     *               shipped with PowerJ
     */
    public record LoadedModule(String name, String version, List<String> cmdlets, String source) {

        /** Originating jar or directory, or {@code (built-in)} in the current language. */
        @Override
        public String source() {
            return source == null ? Messages.get("module.builtin") : source;
        }
    }

    /** Providers already told where the configuration folder is. */
    private final Set<CmdletProvider> initialized = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Registered> byName = new LinkedHashMap<>();
    private final Map<String, Registered> byQualifiedName = new LinkedHashMap<>();
    private final Map<String, LoadedModule> modules = new LinkedHashMap<>();
    /** The prefix of the qualified names of each module, by module name: the last segment, or the whole name. */
    private final Map<String, String> prefixes = new LinkedHashMap<>();

    /** Cmdlets of the startup modules. */
    public static CmdletRegistry discover() {
        var registry = new CmdletRegistry();
        for (CmdletProvider provider : ServiceLoader.load(CmdletProvider.class)) {
            Module module = provider.getClass().getModule();
            registry.addModule(module, Optional.empty(), provider, provider.cmdlets()).forEach(LOG::warning);
        }
        return registry;
    }

    public static CmdletRegistry of(Collection<? extends Cmdlet<?, ?, ?>> cmdlets) {
        var registry = new CmdletRegistry();
        cmdlets.forEach(registry::register);
        return registry;
    }

    /** Registers a cmdlet; a name already taken keeps its first owner (FR-17). */
    public void register(Cmdlet<?, ?, ?> cmdlet) {
        add(qualified(registered(cmdlet, null))).ifPresent(LOG::warning);
    }

    /**
     * Registers the cmdlets of a module.
     *
     * @return warnings (invalid cmdlet, name already in use)
     */
    synchronized List<String> addModule(Module module, Optional<Path> source, CmdletProvider provider,
                                        List<? extends Cmdlet<?, ?, ?>> cmdlets) {
        String moduleName = moduleName(module);
        List<String> warnings = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Cmdlet<?, ?, ?> cmdlet : cmdlets) {
            Registered registered;
            try {
                registered = qualified(registered(cmdlet, provider));
            } catch (RuntimeException e) {
                warnings.add(Messages.get("module.cmdletIgnored", moduleName, e.getMessage()));
                continue;
            }
            add(registered).ifPresent(warnings::add);
            names.add(byName.get(registered.name()) == registered ? registered.name() : registered.qualifiedName());
        }
        String version = module.getDescriptor() == null ? "" : module.getDescriptor().rawVersion().orElse("");
        LoadedModule loaded = modules.get(moduleName);
        if (loaded != null) {
            List<String> all = new ArrayList<>(loaded.cmdlets());
            all.addAll(names);
            names = all;
        }
        modules.put(moduleName, new LoadedModule(moduleName, version, List.copyOf(names),
                source.map(Path::toString).orElse(null)));
        return warnings;
    }

    /** Gives the cmdlet the prefix of its module, the same for all its cmdlets and different from any other module's. */
    private synchronized Registered qualified(Registered registered) {
        String prefix = prefixes.get(registered.module());
        if (prefix == null) {
            String alias = alias(registered.module());
            prefix = prefixes.containsValue(alias) ? registered.module() : alias;
            prefixes.put(registered.module(), prefix);
        }
        return registered.withPrefix(prefix);
    }

    private synchronized Optional<String> add(Registered registered) {
        byQualifiedName.putIfAbsent(registered.qualifiedName(), registered);
        Registered owner = byName.putIfAbsent(registered.name(), registered);
        if (owner == null) {
            return Optional.empty();
        }
        return Optional.of(Messages.get("cmdlet.nameConflict", registered.name(), registered.module(), owner.module(),
                registered.qualifiedName()));
    }

    private static Registered registered(Cmdlet<?, ?, ?> cmdlet, CmdletProvider provider) {
        Class<?> type = cmdlet.getClass();
        CmdletInfo info = type.getAnnotation(CmdletInfo.class);
        if (info == null) {
            throw new IllegalArgumentException(Messages.get("cmdlet.noAnnotation", type.getName()));
        }
        Type[] arguments = cmdletTypeArguments(type);
        if (!(arguments[0] instanceof Class<?> parameters) || !parameters.isRecord()) {
            throw new IllegalArgumentException(Messages.get("cmdlet.parametersNotRecord", type.getName()));
        }
        OptionBinder.validate(parameters.asSubclass(Record.class));
        Class<?> input = rawType(arguments[1]);
        Class<?> output = rawType(arguments[2]);
        return new Registered(cmdlet, info, parameters.asSubclass(Record.class), input, output,
                moduleName(type.getModule()), provider, alias(moduleName(type.getModule())));
    }

    private static String moduleName(Module module) {
        return module.getName() == null ? Messages.get("module.unnamed") : module.getName();
    }

    /** Short prefix of a module: last segment of its name ({@code com.example.greet} → {@code greet}). */
    static String alias(String moduleName) {
        return moduleName.substring(moduleName.lastIndexOf('.') + 1);
    }

    private static Class<?> rawType(Type type) {
        return type instanceof Class<?> c ? c
                : type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : Object.class;
    }

    private static Type[] cmdletTypeArguments(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Type t : c.getGenericInterfaces()) {
                if (t instanceof ParameterizedType p && p.getRawType() == Cmdlet.class) {
                    return p.getActualTypeArguments();
                }
            }
        }
        throw new IllegalArgumentException(Messages.get("cmdlet.typeArguments", type.getName()));
    }

    /** By short name ({@code greet}) or name qualified by the module ({@code greet:greet}, FR-17). */
    public synchronized Optional<Registered> find(String name) {
        Registered registered = byName.get(name);
        if (registered == null && name.indexOf(':') > 0) {
            registered = byQualifiedName.get(name);
        }
        return Optional.ofNullable(registered);
    }

    /** Cmdlets accessible by their short name. */
    public synchronized List<Registered> all() {
        return List.copyOf(byName.values());
    }

    /** Cmdlets shadowed by a namesake, accessible only by their qualified name. */
    public synchronized List<Registered> shadowed() {
        return byQualifiedName.values().stream().filter(r -> byName.get(r.name()) != r).toList();
    }

    /**
     * Tells the providers that were not told yet where the configuration folder is, so that they can create their
     * default files ({@link CmdletProvider#initialize}). A provider that fails is reported and not asked again.
     *
     * @return warnings, one per provider that could not prepare the folder
     */
    public synchronized List<String> initialize(Optional<Path> configDirectory) {
        List<String> warnings = new ArrayList<>();
        if (configDirectory.isEmpty()) {
            return warnings;
        }
        for (Registered registered : byName.values()) {
            CmdletProvider provider = registered.provider();
            if (provider == null || !initialized.add(provider)) {
                continue;
            }
            try {
                provider.initialize(configDirectory.get());
            } catch (IOException | RuntimeException e) {
                LOG.log(java.util.logging.Level.WARNING, "Module " + registered.module() + " could not prepare "
                        + configDirectory.get(), e);
                warnings.add(Messages.get("module.initializeFailed", registered.module(), e.getMessage()));
            }
        }
        return warnings;
    }

    public synchronized boolean hasModule(String name) {
        return modules.containsKey(name);
    }

    public synchronized List<LoadedModule> modules() {
        return List.copyOf(modules.values());
    }
}
