package io.powerj.core.exec;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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

/** Cmdlets disponibles, découverts par {@link ServiceLoader} (spécification §4). */
public final class CmdletRegistry {

    private static final Logger LOG = Logger.getLogger(CmdletRegistry.class.getName());

    /** Cmdlet enregistré avec ses métadonnées. */
    public record Registered(Cmdlet<?, ?, ?> cmdlet, CmdletInfo info, Class<? extends Record> parameters,
                             Class<?> output, String module) {

        public String name() {
            return info.name();
        }
    }

    private final Map<String, Registered> byName = new LinkedHashMap<>();

    /** Cmdlets des modules du démarrage. */
    public static CmdletRegistry discover() {
        var registry = new CmdletRegistry();
        for (CmdletProvider provider : ServiceLoader.load(CmdletProvider.class)) {
            provider.cmdlets().forEach(registry::register);
        }
        return registry;
    }

    public static CmdletRegistry of(Collection<? extends Cmdlet<?, ?, ?>> cmdlets) {
        var registry = new CmdletRegistry();
        cmdlets.forEach(registry::register);
        return registry;
    }

    /** Enregistre un cmdlet ; un nom déjà pris est ignoré avec un avertissement (FR-17). */
    public void register(Cmdlet<?, ?, ?> cmdlet) {
        Class<?> type = cmdlet.getClass();
        CmdletInfo info = type.getAnnotation(CmdletInfo.class);
        if (info == null) {
            throw new IllegalArgumentException(type.getName() + " n'a pas d'annotation @CmdletInfo");
        }
        Type[] arguments = cmdletTypeArguments(type);
        if (!(arguments[0] instanceof Class<?> parameters) || !parameters.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " : les paramètres doivent être un record");
        }
        Class<?> output = arguments[2] instanceof Class<?> c ? c
                : arguments[2] instanceof ParameterizedType p ? (Class<?>) p.getRawType() : Object.class;
        var registered = new Registered(cmdlet, info, parameters.asSubclass(Record.class), output,
                type.getModule().getName() == null ? "(sans module)" : type.getModule().getName());
        if (byName.putIfAbsent(info.name(), registered) != null) {
            LOG.warning(() -> "Cmdlet " + info.name() + " de " + registered.module()
                    + " ignoré : nom déjà utilisé par " + byName.get(info.name()).module());
        }
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

    public Optional<Registered> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Registered> all() {
        return List.copyOf(byName.values());
    }
}
