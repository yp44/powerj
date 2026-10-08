package io.powerj.cmdlets;

import java.io.File;
import java.util.Map;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/** {@code env}: session environment variables (specification FR-36b). */
@CmdletInfo(name = "env", category = "System", summary = "Shows or changes the session environment variables",
        examples = {"env", "env PATH", "env --set MAVEN_OPTS=-Xmx2g", "env --append PATH C:\\tools", "env --unset MAVEN_OPTS",
                "(env PATH).value"})
public final class Env implements Cmdlet<Env.Params, Void, EnvVar> {

    /** Parameters of {@code env}. */
    public record Params(
            @Option(position = 0, description = "Name of a variable to show")
            String name,
            @Option(shortName = 's', description = "Creates or changes a variable: NAME=value")
            String set,
            @Option(description = "Removes a variable")
            String unset,
            @Option(description = "Appends a value to a list (e.g. PATH): --append NAME value")
            String append,
            @Option(description = "Prepends a value to a list: --prepend NAME value")
            String prepend,
            @Option(position = 1, description = "Value used by --append and --prepend")
            String value) {
    }

    @Override
    public void begin(Params p, CmdletContext<EnvVar> context) {
        Map<String, String> env = context.environment();
        int actions = count(p.set(), p.unset(), p.append(), p.prepend());
        if (actions > 1) {
            throw new IllegalArgumentException(Messages.get("env.one.action"));
        }
        if (p.set() != null) {
            int equals = p.set().indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException(Messages.get("env.set.format"));
            }
            env.put(p.set().substring(0, equals), p.set().substring(equals + 1));
        } else if (p.unset() != null) {
            if (env.remove(p.unset()) == null) {
                context.error(Messages.get("env.variable.missing", p.unset()));
            }
        } else if (p.append() != null || p.prepend() != null) {
            String name = p.append() != null ? p.append() : p.prepend();
            // "env --append PATH C:\tools": PATH is read as the value of --append, C:\tools at position 0.
            String addition = p.value() != null ? p.value() : p.name();
            if (addition == null) {
                throw new IllegalArgumentException(Messages.get("env.value.expected"));
            }
            String current = env.get(name);
            String separator = File.pathSeparator;
            String updated = current == null || current.isEmpty() ? addition
                    : p.append() != null ? current + separator + addition : addition + separator + current;
            env.put(name, updated);
        } else if (p.name() != null) {
            String value = env.get(p.name());
            if (value == null) {
                context.error(Messages.get("env.variable.missing", p.name()));
            } else {
                context.emit(new EnvVar(canonicalName(env, p.name()), value));
            }
        } else {
            env.forEach((name, value) -> context.emit(new EnvVar(name, value)));
        }
    }

    /** Name as stored ({@code Path} on Windows when {@code PATH} is requested). */
    private static String canonicalName(Map<String, String> env, String name) {
        return env.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(name);
    }

    private static int count(Object... values) {
        int n = 0;
        for (Object v : values) {
            if (v != null) {
                n++;
            }
        }
        return n;
    }
}
