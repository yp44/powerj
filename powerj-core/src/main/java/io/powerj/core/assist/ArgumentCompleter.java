package io.powerj.core.assist;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.powerj.core.assist.Completions.Candidate;
import io.powerj.core.assist.Completions.Result;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.command.OptionBinder;
import io.powerj.core.i18n.Messages;
import io.powerj.core.runtime.CommandCatalog;

/** Suggestions for the arguments of a command: options (FR-22), their values (FR-23) and paths (FR-25). */
final class ArgumentCompleter {

    private final CommandCatalog catalog;
    private final PathCompleter paths;
    private final PackageCompleter packages;

    ArgumentCompleter(CommandCatalog catalog, PathCompleter paths, PackageCompleter packages) {
        this.catalog = catalog;
        this.paths = paths;
        this.packages = packages;
    }

    Result arguments(String line, Frame frame, int cursor) {
        int wordStart = cursor;
        while (wordStart > frame.stageStart() && !isArgumentBoundary(line.charAt(wordStart - 1))) {
            wordStart--;
        }
        String word = line.substring(wordStart, cursor);
        List<String> words = LineText.splitWords(line.substring(frame.stageStart(), wordStart));
        String command = words.getFirst();
        boolean forceNative = command.startsWith("^");
        String name = forceNative ? command.substring(1) : command;
        var cmdlet = forceNative ? Optional.<CmdletRegistry.Registered>empty() : catalog.cmdlet(name);

        if (name.equals("import")) {
            return packages.imports(wordStart, word);
        }
        if (cmdlet.isPresent()) {
            List<OptionBinder.OptionSpec> specs = OptionBinder.specs(cmdlet.get().parameters());
            if (word.startsWith("-")) {
                return new Result(wordStart, word, options(cmdlet.get(), specs, words));
            }
            Optional<OptionBinder.OptionSpec> expecting = valueOf(specs, words.getLast());
            if (expecting.isPresent()) {
                return optionValues(expecting.get(), line, wordStart, cursor);
            }
            // Textual positional parameter (often a path: ls docs); not for a block (where, map).
            boolean pathPositional = specs.stream().anyMatch(s -> s.isPositional()
                    && (s.component().getType() == Path.class || s.component().getType() == String.class
                        || isPathList(s) || s.isList() && s.component().getGenericType().getTypeName().contains("String")));
            return pathPositional ? paths.paths(line, wordStart, cursor) : Result.none(cursor);
        }
        if (catalog.builtinNames().contains(name) && !name.equals("cd")) {
            return Result.none(cursor);
        }
        return paths.paths(line, wordStart, cursor); // cd and native commands: paths (FR-25)
    }

    private static boolean isArgumentBoundary(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '|' || c == ';' || c == '(' || c == '>';
    }

    private static boolean isPathList(OptionBinder.OptionSpec spec) {
        return spec.isList() && spec.component().getGenericType().getTypeName().contains("Path");
    }

    /** Options of the cmdlet, excluding those already typed (FR-22). */
    private static List<Candidate> options(CmdletRegistry.Registered cmdlet, List<OptionBinder.OptionSpec> specs,
                                           List<String> words) {
        List<Candidate> candidates = new ArrayList<>();
        for (var spec : specs) {
            if (spec.isPositional() && spec.shortName() == '\0' && !spec.isFlag()) {
                continue; // purely positional
            }
            boolean used = words.stream().anyMatch(w -> w.equals("--" + spec.longName())
                    || w.startsWith("--" + spec.longName() + "=")
                    || (spec.shortName() != '\0' && w.matches("-[^-]*" + spec.shortName() + "[^-]*")));
            if (!used || spec.isList()) {
                String label = "--" + spec.longName() + (spec.shortName() != '\0' ? ", -" + spec.shortName() : "");
                candidates.add(new Candidate("--" + spec.longName(), label,
                        cmdlet.optionDescription(spec.longName(), spec.description()), true));
            }
        }
        if (words.stream().noneMatch(w -> w.startsWith("--on-error"))) {
            candidates.add(new Candidate("--on-error", "--on-error", Messages.get("completion.onError"), true));
        }
        candidates.add(new Candidate("--help", "--help", Messages.get("completion.help"), true));
        return candidates;
    }

    /** Option expecting a value, designated by the last typed word ({@code --filter}, {@code -f}). */
    private static Optional<OptionBinder.OptionSpec> valueOf(List<OptionBinder.OptionSpec> specs, String word) {
        for (var spec : specs) {
            if (spec.isFlag()) {
                continue;
            }
            if (word.equals("--" + spec.longName())
                    || (spec.shortName() != '\0' && word.matches("-[^-]*" + spec.shortName()))) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    /** Values of an option according to its type (FR-23): enum constants, paths. */
    private Result optionValues(OptionBinder.OptionSpec spec, String line, int start, int cursor) {
        Class<?> type = spec.component().getType();
        if (type.isEnum()) {
            List<Candidate> candidates = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                String value = ((Enum<?>) constant).name().toLowerCase(Locale.ROOT);
                candidates.add(new Candidate(value, value, "", true));
            }
            return new Result(start, line.substring(start, cursor), candidates);
        }
        if (type == Path.class || isPathList(spec)) {
            return paths.paths(line, start, cursor);
        }
        return Result.none(cursor);
    }
}
