package io.powerj.core.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import io.powerj.core.assist.Completions.Candidate;
import io.powerj.core.assist.Completions.Result;
import io.powerj.core.i18n.Messages;
import io.powerj.core.java.JavaIndex;
import io.powerj.core.runtime.CommandCatalog;

/** Suggestions from the Java packages and classes: {@code import}, {@code java.util.} and the imported classes. */
final class PackageCompleter {

    private final CommandCatalog catalog;
    private final JavaIndex index;

    PackageCompleter(CommandCatalog catalog, JavaIndex index) {
        this.catalog = catalog;
        this.index = index;
    }

    Result imports(int start, String word) {
        int dot = word.lastIndexOf('.');
        if (dot < 0) {
            List<Candidate> roots = new ArrayList<>();
            new TreeSet<>(index.packages().stream().map(p -> p.split("\\.")[0]).toList())
                    .forEach(r -> roots.add(new Candidate(r + ".", r, Messages.get("completion.package"), false)));
            return new Result(start, word, roots);
        }
        String pkg = word.substring(0, dot);
        List<Candidate> candidates = new ArrayList<>();
        for (Candidate c : subpackages(pkg)) {
            candidates.add(new Candidate(pkg + "." + c.value() + ".", c.display(), c.description(), c.complete()));
        }
        for (Candidate c : packageClasses(pkg)) {
            candidates.add(new Candidate(pkg + "." + c.value(), c.display(), c.description(), c.complete()));
        }
        if (index.packages().contains(pkg)) {
            candidates.add(new Candidate(pkg + ".*", "*", Messages.get("completion.allClasses"), true));
        }
        return new Result(start, word, candidates);
    }

    /** Simple names of the imported classes (default imports and {@code import}). */
    List<String> importedClasses() {
        TreeSet<String> names = new TreeSet<>();
        for (String imported : catalog.session().java().imports()) {
            if (imported.endsWith(".*")) {
                names.addAll(index.classes(imported.substring(0, imported.length() - 2)));
            } else {
                names.add(imported.substring(imported.lastIndexOf('.') + 1));
            }
        }
        return List.copyOf(names);
    }

    /** Subpackages and classes of a package ({@code java.util.} → {@code List}, {@code concurrent}…). */
    List<Candidate> packageMembers(String pkg) {
        List<Candidate> candidates = new ArrayList<>(subpackages(pkg));
        candidates.addAll(packageClasses(pkg));
        return candidates;
    }

    private List<Candidate> subpackages(String pkg) {
        TreeSet<String> subpackages = new TreeSet<>();
        for (String p : index.packages()) {
            if (p.startsWith(pkg + ".")) {
                subpackages.add(p.substring(pkg.length() + 1).split("\\.")[0]);
            }
        }
        String description = Messages.get("completion.package");
        return subpackages.stream().map(s -> new Candidate(s, s, description, false)).toList();
    }

    private List<Candidate> packageClasses(String pkg) {
        String description = Messages.get("completion.class");
        return index.classes(pkg).stream().map(c -> new Candidate(c, c, description, false)).toList();
    }
}
