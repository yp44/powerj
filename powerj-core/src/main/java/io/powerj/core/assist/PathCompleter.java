package io.powerj.core.assist;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import io.powerj.core.assist.Completions.Candidate;
import io.powerj.core.assist.Completions.Result;
import io.powerj.core.i18n.Messages;
import io.powerj.core.nativeexec.Platform;
import io.powerj.core.runtime.CommandCatalog;

/** Suggestions of file paths (FR-25), for the word starting at a position of the line (possibly quoted). */
final class PathCompleter {

    private static final int MAX_FILES = 500;

    private final CommandCatalog catalog;

    PathCompleter(CommandCatalog catalog) {
        this.catalog = catalog;
    }

    /** File paths for the word starting at {@code start} (possibly quoted). */
    Result paths(String line, int start, int cursor) {
        String raw = line.substring(start, cursor);
        boolean quoted = raw.startsWith("\"");
        String text = quoted ? unescape(raw.substring(1)) : raw;
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        String dirPart = text.substring(0, slash + 1);
        String prefix = text.substring(slash + 1);
        Path base;
        try {
            base = dirPart.isEmpty() ? catalog.session().currentDirectory()
                    : dirPart.startsWith("~") ? catalog.session().home().resolve(dirPart.substring(1).replaceFirst("^[/\\\\]", ""))
                    : catalog.session().currentDirectory().resolve(dirPart);
        } catch (InvalidPathException e) {
            return Result.none(cursor);
        }
        if (!Files.isDirectory(base)) {
            return Result.none(cursor);
        }
        char separator = dirPart.contains("/") ? '/' : dirPart.contains("\\") ? '\\'
                : Platform.isWindows() ? '\\' : '/';
        boolean ignoreCase = Platform.isWindows();
        List<Candidate> candidates = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(base)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean matches = ignoreCase ? name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))
                        : name.startsWith(prefix);
                if (!matches || (name.startsWith(".") && !prefix.startsWith("."))) {
                    continue;
                }
                boolean dir = Files.isDirectory(entry);
                String value = dirPart + name + (dir ? String.valueOf(separator) : "");
                candidates.add(new Candidate(value, name + (dir ? separator : ""), dir ? Messages.get("completion.directory") : "", !dir));
                if (candidates.size() >= MAX_FILES) {
                    break;
                }
            }
        } catch (IOException | SecurityException e) {
            return Result.none(cursor);
        }
        candidates.sort(Comparator.comparing(Candidate::value));
        return new Result(start, text, candidates);
    }

    private static String unescape(String text) {
        var out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
