package io.powerj.cmdlets;

import java.io.File;
import java.util.Map;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/** {@code env}: session environment variables (specification FR-36b). */
@CmdletInfo(name = "env", category = "Système", summary = "Affiche ou modifie les variables d'environnement de la session",
        examples = {"env", "env PATH", "env --set MAVEN_OPTS=-Xmx2g", "env --append PATH C:\\tools", "env --unset MAVEN_OPTS",
                "(env PATH).value"})
public final class Env implements Cmdlet<Env.Params, Void, EnvVar> {

    /** Parameters of {@code env}. */
    public record Params(
            @Option(position = 0, description = "Nom d'une variable à afficher")
            String name,
            @Option(shortName = 's', description = "Crée ou modifie une variable : NOM=valeur")
            String set,
            @Option(description = "Supprime une variable")
            String unset,
            @Option(description = "Ajoute une valeur en fin de liste (ex. PATH) : --append NOM valeur")
            String append,
            @Option(description = "Ajoute une valeur en tête de liste : --prepend NOM valeur")
            String prepend,
            @Option(position = 1, description = "Valeur utilisée par --append et --prepend")
            String value) {
    }

    @Override
    public void begin(Params p, CmdletContext<EnvVar> context) {
        Map<String, String> env = context.environment();
        int actions = count(p.set(), p.unset(), p.append(), p.prepend());
        if (actions > 1) {
            throw new IllegalArgumentException("une seule action à la fois parmi --set, --unset, --append, --prepend");
        }
        if (p.set() != null) {
            int equals = p.set().indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("--set attend NOM=valeur");
            }
            env.put(p.set().substring(0, equals), p.set().substring(equals + 1));
        } else if (p.unset() != null) {
            if (env.remove(p.unset()) == null) {
                context.error("variable absente : " + p.unset());
            }
        } else if (p.append() != null || p.prepend() != null) {
            String name = p.append() != null ? p.append() : p.prepend();
            // "env --append PATH C:\tools": PATH is read as the value of --append, C:\tools at position 0.
            String addition = p.value() != null ? p.value() : p.name();
            if (addition == null) {
                throw new IllegalArgumentException("valeur attendue : env --append NOM valeur");
            }
            String current = env.get(name);
            String separator = File.pathSeparator;
            String updated = current == null || current.isEmpty() ? addition
                    : p.append() != null ? current + separator + addition : addition + separator + current;
            env.put(name, updated);
        } else if (p.name() != null) {
            String value = env.get(p.name());
            if (value == null) {
                context.error("variable absente : " + p.name());
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
