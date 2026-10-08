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
import java.util.ServiceLoader;
import java.util.logging.Logger;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.CmdletProvider;

/**
 * Cmdlets disponibles : ceux des modules du démarrage, découverts par {@link ServiceLoader}, puis ceux des
 * modules tiers chargés par {@link ModuleLoader} (spécification §4). Modifiable à chaud ({@code mod-load}) :
 * les méthodes sont synchronisées.
 */
public final class CmdletRegistry {

    private static final Logger LOG = Logger.getLogger(CmdletRegistry.class.getName());

    /** Cmdlet enregistré avec ses métadonnées. */
    public record Registered(Cmdlet<?, ?, ?> cmdlet, CmdletInfo info, Class<? extends Record> parameters,
                             Class<?> input, Class<?> output, String module) {

        public String name() {
            return info.name();
        }

        /** {@code true} si le cmdlet lit les objets du pipeline ({@code I} autre que {@code Void}). */
        public boolean readsInput() {
            return input != Void.class;
        }

        /** Nom qualifié, toujours utilisable : {@code greet:greet} (FR-17). */
        public String qualifiedName() {
            return alias(module) + ":" + name();
        }
    }

    /**
     * Module chargé (ligne de {@code mod-list}).
     *
     * @param source jar ou dossier d'origine ; {@code (intégré)} pour les modules livrés avec PowerJ
     */
    public record LoadedModule(String name, String version, List<String> cmdlets, String source) { }

    private final Map<String, Registered> byName = new LinkedHashMap<>();
    private final Map<String, Registered> byQualifiedName = new LinkedHashMap<>();
    private final Map<String, LoadedModule> modules = new LinkedHashMap<>();

    /** Cmdlets des modules du démarrage. */
    public static CmdletRegistry discover() {
        var registry = new CmdletRegistry();
        for (CmdletProvider provider : ServiceLoader.load(CmdletProvider.class)) {
            Module module = provider.getClass().getModule();
            registry.addModule(module, Optional.empty(), provider.cmdlets()).forEach(LOG::warning);
        }
        return registry;
    }

    public static CmdletRegistry of(Collection<? extends Cmdlet<?, ?, ?>> cmdlets) {
        var registry = new CmdletRegistry();
        cmdlets.forEach(registry::register);
        return registry;
    }

    /** Enregistre un cmdlet ; un nom déjà pris garde son premier propriétaire (FR-17). */
    public void register(Cmdlet<?, ?, ?> cmdlet) {
        add(registered(cmdlet)).ifPresent(LOG::warning);
    }

    /**
     * Enregistre les cmdlets d'un module.
     *
     * @return avertissements (cmdlet invalide, nom déjà utilisé)
     */
    synchronized List<String> addModule(Module module, Optional<Path> source, List<? extends Cmdlet<?, ?, ?>> cmdlets) {
        String moduleName = moduleName(module);
        List<String> warnings = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Cmdlet<?, ?, ?> cmdlet : cmdlets) {
            Registered registered;
            try {
                registered = registered(cmdlet);
            } catch (RuntimeException e) {
                warnings.add("module " + moduleName + " : cmdlet ignoré : " + e.getMessage());
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
                source.map(Path::toString).orElse("(intégré)")));
        return warnings;
    }

    private synchronized Optional<String> add(Registered registered) {
        byQualifiedName.putIfAbsent(registered.qualifiedName(), registered);
        Registered owner = byName.putIfAbsent(registered.name(), registered);
        if (owner == null) {
            return Optional.empty();
        }
        return Optional.of("cmdlet « " + registered.name() + " » du module " + registered.module()
                + " : nom déjà utilisé par " + owner.module() + " ; accessible par " + registered.qualifiedName());
    }

    private static Registered registered(Cmdlet<?, ?, ?> cmdlet) {
        Class<?> type = cmdlet.getClass();
        CmdletInfo info = type.getAnnotation(CmdletInfo.class);
        if (info == null) {
            throw new IllegalArgumentException(type.getName() + " n'a pas d'annotation @CmdletInfo");
        }
        Type[] arguments = cmdletTypeArguments(type);
        if (!(arguments[0] instanceof Class<?> parameters) || !parameters.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " : les paramètres doivent être un record");
        }
        Class<?> input = rawType(arguments[1]);
        Class<?> output = rawType(arguments[2]);
        return new Registered(cmdlet, info, parameters.asSubclass(Record.class), input, output,
                moduleName(type.getModule()));
    }

    private static String moduleName(Module module) {
        return module.getName() == null ? "(sans module)" : module.getName();
    }

    /** Préfixe court d'un module : dernier segment de son nom ({@code com.example.greet} → {@code greet}). */
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
        throw new IllegalArgumentException(type.getName() + " doit implémenter Cmdlet<P, I, O> avec des types explicites");
    }

    /** Par nom court ({@code greet}) ou qualifié par le module ({@code greet:greet}, FR-17). */
    public synchronized Optional<Registered> find(String name) {
        Registered registered = byName.get(name);
        if (registered == null && name.indexOf(':') > 0) {
            registered = byQualifiedName.get(name);
        }
        return Optional.ofNullable(registered);
    }

    /** Cmdlets accessibles par leur nom court. */
    public synchronized List<Registered> all() {
        return List.copyOf(byName.values());
    }

    /** Cmdlets masqués par un homonyme, accessibles seulement par leur nom qualifié. */
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
