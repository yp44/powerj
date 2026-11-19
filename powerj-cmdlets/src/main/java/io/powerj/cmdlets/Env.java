package io.powerj.cmdlets;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Map;

import io.powerj.fs.Host;
import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.Previewable;
import io.powerj.api.UsageException;

/** {@code env}: session environment variables (specification FR-36b). */
@CmdletInfo(name = "env", preview = true, category = "System", summary = "Shows or changes the session environment variables",
        examples = {"env", "env PATH", "env --set MAVEN_OPTS=-Xmx2g", "env --append PATH C:\\tools", "env --unset MAVEN_OPTS",
                "(env PATH).value"})
public final class Env implements Cmdlet<Env.Params, Void, EnvVar>, Previewable<Env.Params> {

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

    /** Only reading is previewed (FR-66): {@code --set}, {@code --unset}, {@code --append}, {@code --prepend} change the session. */
    @Override
    public boolean canPreview(Params p) {
        return count(p.set(), p.unset(), p.append(), p.prepend()) == 0;
    }

    @Override
    public void begin(Params p, CmdletContext<EnvVar> context) {
        Map<String, String> env = context.environment(); // a copy: it is changed through the context
        int actions = count(p.set(), p.unset(), p.append(), p.prepend());
        if (actions > 1) {
            throw new UsageException(Messages.get("env.one.action"));
        }
        if (p.set() != null) {
            int equals = p.set().indexOf('=');
            if (equals <= 0) {
                throw new UsageException(Messages.get("env.set.format"));
            }
            context.setEnvironment(p.set().substring(0, equals), p.set().substring(equals + 1));
        } else if (p.unset() != null) {
            if (!context.unsetEnvironment(p.unset())) {
                context.error(Messages.get("env.variable.missing", p.unset()));
            }
        } else if (p.append() != null || p.prepend() != null) {
            String option = p.append() != null ? "--append" : "--prepend";
            String name = p.append() != null ? p.append() : p.prepend();
            if (!isVariableName(name)) {
                // "env PATH --append /x" binds /x to the option: it would create a variable called /x
                throw new UsageException(Messages.get("env.name.invalid", option, name));
            }
            if (p.name() != null && p.value() != null) {
                throw new UsageException(Messages.get("env.value.twice", option, p.name(), p.value()));
            }
            // "env --append PATH C:\tools": PATH is read as the value of --append, C:\tools at position 0.
            String addition = p.value() != null ? p.value() : p.name();
            if (addition == null) {
                throw new UsageException(Messages.get("env.value.expected"));
            }
            String current = env.get(name);
            String separator = File.pathSeparator;
            List<String> entries = current == null || current.isEmpty() ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList(current.split(Pattern.quote(separator), -1)));
            if (p.append() != null) {
                if (entries.contains(addition)) {
                    return; // already in the list: a second entry would only slow every lookup down
                }
                entries.add(addition);
            } else {
                // --prepend gives a folder priority: one that is already there, later, is moved to the head
                entries.remove(addition);
                entries.add(0, addition);
            }
            String updated = String.join(separator, entries);
            context.setEnvironment(name, updated);
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

    /** Could this be the name of a variable? A path or an assignment is not ({@code /x}, {@code C:\\tools}, {@code A=b}). */
    private static boolean isVariableName(String name) {
        return !name.isBlank() && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('=') < 0;
    }

    /** Name as stored ({@code Path} on Windows when {@code PATH} is requested). */
    private static String canonicalName(Map<String, String> env, String name) {
        if (!Host.windows()) {
            return name; // the names of the variables are case-sensitive, except on Windows
        }
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
