package io.powerj.core.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.powerj.core.assist.Completions.Candidate;
import io.powerj.core.assist.Completions.Result;
import io.powerj.core.error.PjException;
import io.powerj.core.i18n.Messages;
import io.powerj.core.runtime.CommandCatalog;

/** Suggestions of names in an expression: variables, lambda parameters, keywords and imported classes. */
final class NameCompleter {

    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "now", "new");

    private final CommandCatalog catalog;
    private final TypeInference types;
    private final PackageCompleter packages;

    NameCompleter(CommandCatalog catalog, TypeInference types, PackageCompleter packages) {
        this.catalog = catalog;
        this.types = types;
        this.packages = packages;
    }

    Result variables(int start, String word, boolean inBlock) {
        List<Candidate> candidates = new ArrayList<>();
        if (inBlock) {
            candidates.add(new Candidate("$_", "$_", Messages.get("completion.current"), false));
        }
        for (String name : catalog.session().variableNames()) {
            Object value;
            try {
                value = catalog.session().variable(name);
            } catch (PjException e) {
                continue;
            }
            candidates.add(new Candidate("$" + name, "$" + name,
                    value == null ? "null" : value.getClass().getSimpleName(), false));
        }
        return new Result(start, word, candidates);
    }

    /** In an expression: lambda parameters, imported classes, keywords. */
    Result identifiers(String line, Frame frame, int start, String fragment) {
        List<Candidate> candidates = new ArrayList<>();
        Scope scope = types.scope(line, frame);
        scope.parameters().keySet().forEach(p -> candidates.add(new Candidate(p, p, Messages.get("completion.parameter"), false)));
        String before = line.substring(Math.max(0, start - 4), start);
        if (before.endsWith("new ")) {
            packages.importedClasses().forEach(c -> candidates.add(new Candidate(c, c, Messages.get("completion.javaClass"), false)));
            return new Result(start, fragment, candidates);
        }
        KEYWORDS.stream().sorted().forEach(k -> candidates.add(new Candidate(k, k, Messages.get("completion.keyword"), false)));
        if (!fragment.isEmpty() && Character.isUpperCase(fragment.charAt(0))) {
            packages.importedClasses().forEach(c -> candidates.add(new Candidate(c, c, Messages.get("completion.javaClass"), false)));
        }
        return new Result(start, fragment, candidates);
    }
}
