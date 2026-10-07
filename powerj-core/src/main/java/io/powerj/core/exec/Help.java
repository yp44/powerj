package io.powerj.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.powerj.api.CmdletInfo;

/** Aide générée à partir des métadonnées des cmdlets (spécification FR-29, FR-45). */
final class Help {

    /** Commandes internes : nom → (usage, description). */
    private static final Map<String, String[]> BUILTINS = new LinkedHashMap<>();

    static {
        BUILTINS.put("cd", new String[] {"cd [dossier | - | ~]", "Change le dossier courant (- : précédent, ~ : utilisateur)"});
        BUILTINS.put("pwd", new String[] {"pwd", "Affiche le dossier courant"});
        BUILTINS.put("which", new String[] {"which <nom>...", "Indique ce qui sera exécuté : commande interne, cmdlet ou programme"});
        BUILTINS.put("help", new String[] {"help [commande] | help members <valeur>", "Aide sur les commandes ; membres d'un objet"});
        BUILTINS.put("history", new String[] {"history [--clear]", "Historique numéroté ; !n, !!, !texte pour ré-exécuter"});
        BUILTINS.put("exit", new String[] {"exit [code]", "Quitte le shell"});
    }

    private Help() {
    }

    static boolean isDocumentedBuiltin(String name) {
        return BUILTINS.containsKey(name);
    }

    static List<Object> run(List<Object> args, CmdletRegistry registry) {
        if (args.isEmpty()) {
            return overview(registry);
        }
        if (args.getFirst() instanceof String word && word.equals("members")) {
            if (args.size() != 2) {
                throw new PjException("help members : une valeur attendue, ex. help members $f[0]");
            }
            Object value = args.get(1);
            if (value == null) {
                throw new PjException("help members : la valeur est nulle");
            }
            return List.copyOf(Members.of(value));
        }
        if (args.size() > 1) {
            throw new PjException("help : une seule commande à la fois");
        }
        String name = Values.text(args.getFirst());
        var cmdlet = registry.find(name);
        if (cmdlet.isPresent()) {
            return List.copyOf(cmdlet(cmdlet.get()));
        }
        String[] builtin = BUILTINS.get(name);
        if (builtin != null) {
            return List.of(name + " — " + builtin[1], "Usage : " + builtin[0]);
        }
        throw new PjException("help : commande inconnue : " + name);
    }

    private static List<Object> overview(CmdletRegistry registry) {
        List<Object> lines = new ArrayList<>();
        lines.add("Commandes internes");
        BUILTINS.forEach((name, doc) -> lines.add("  %-10s %s".formatted(name, doc[1])));
        Map<String, List<CmdletInfo>> byCategory = new TreeMap<>();
        for (var registered : registry.all()) {
            byCategory.computeIfAbsent(registered.info().category(), _ -> new ArrayList<>()).add(registered.info());
        }
        byCategory.forEach((category, infos) -> {
            lines.add("");
            lines.add(category);
            infos.forEach(info -> lines.add("  %-10s %s".formatted(info.name(), info.summary())));
        });
        lines.add("");
        lines.add("help <commande> : détail d'une commande ; help members <valeur> : propriétés et méthodes d'un objet.");
        lines.add("Les programmes du système (git, notepad…) s'utilisent directement ; ^nom force le programme.");
        return lines;
    }

    static List<String> cmdlet(CmdletRegistry.Registered registered) {
        CmdletInfo info = registered.info();
        List<OptionBinder.OptionSpec> specs = OptionBinder.specs(registered.parameters());
        List<String> lines = new ArrayList<>();
        lines.add(info.name() + " — " + info.summary());
        var usage = new StringBuilder("Usage : ").append(info.name());
        specs.stream().filter(OptionBinder.OptionSpec::isPositional).forEach(s ->
                usage.append(" [").append(s.longName()).append(s.isList() ? "..." : "").append("]"));
        if (specs.stream().anyMatch(s -> !s.isPositional())) {
            usage.append(" [options]");
        }
        lines.add(usage.toString());
        lines.add("");
        lines.add("Options :");
        int width = specs.stream().mapToInt(s -> optionLabel(s).length()).max().orElse(10);
        String format = "  %-" + Math.max(width, "--on-error <mode>".length()) + "s  %s";
        for (var spec : specs) {
            lines.add(format.formatted(optionLabel(spec), spec.description() + (spec.mandatory() ? " (obligatoire)" : "")));
        }
        lines.add(format.formatted("--on-error <mode>", "erreurs non bloquantes : stop, continue (défaut) ou silent"));
        lines.add("");
        lines.add("Sortie : " + registered.output().getSimpleName() + outputComponents(registered.output()));
        lines.add("Module : " + registered.module());
        if (info.examples().length > 0) {
            lines.add("");
            lines.add("Exemples :");
            for (String example : info.examples()) {
                lines.add("  " + example);
            }
        }
        return lines;
    }

    private static String optionLabel(OptionBinder.OptionSpec spec) {
        String label = spec.isPositional() ? "<" + spec.longName() + ">" : spec.display();
        if (!spec.isFlag() && !spec.isPositional()) {
            label += " <valeur>";
        }
        return label;
    }

    private static String outputComponents(Class<?> output) {
        if (!output.isRecord()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (var c : output.getRecordComponents()) {
            names.add(c.getName());
        }
        return " (" + String.join(", ", names) + ")";
    }
}
