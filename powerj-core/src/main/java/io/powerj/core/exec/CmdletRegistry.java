package io.powerj.core.exec;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.ServiceLoader;
import java.util.logging.Logger;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.CmdletProvider;
import io.powerj.api.Language;

/**
 * Available cmdlets: those of the startup modules, discovered by {@link ServiceLoader}, then those of the
 * third-party modules loaded by {@link ModuleLoader} (specification §4). Modifiable at runtime ({@code mod-load}):
 * the methods are synchronized.
 */
public final class CmdletRegistry {

    private static final Logger LOG = Logger.getLogger(CmdletRegistry.class.getName());

    /** Registered cmdlet with its metadata. */
    public record Registered(Cmdlet<?, ?, ?> cmdlet, CmdletInfo info, Class<? extends Record> parameters,
                             Class<?> input, Class<?> output, String module, CmdletProvider provider) {

        public String name() {
            return info.name();
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

        /** Qualified name, always usable: {@code greet:greet} (FR-17). */
        public String qualifiedName() {
            return alias(module) + ":" + name();
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

    private final Map<String, Registered> byName = new LinkedHashMap<>();
    private final Map<String, Registered> byQualifiedName = new LinkedHashMap<>();
    private final Map<String, LoadedModule> modules = new LinkedHashMap<>();

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
        add(registered(cmdlet, null)).ifPresent(LOG::warning);
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
                registered = registered(cmdlet, provider);
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
        Class<?> input = rawType(arguments[1]);
        Class<?> output = rawType(arguments[2]);
        return new Registered(cmdlet, info, parameters.asSubclass(Record.class), input, output,
                moduleName(type.getModule()), provider);
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

    public synchronized boolean hasModule(String name) {
        return modules.containsKey(name);
    }

    public synchronized List<LoadedModule> modules() {
        return List.copyOf(modules.values());
    }
}
