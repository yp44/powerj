package io.powerj.fs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.PatternSyntaxException;

/**
 * Expansion of a word that contains wildcards into the names of the files it matches, as a Unix shell does before it
 * starts a program (specification FR-40). Paths use {@code /}: this is for Linux and macOS, where programs expect the
 * shell to do it.
 *
 * <ul>
 *   <li>every element of the path may hold wildcards: {@code src/*}{@code /pom.xml};</li>
 *   <li>the names found are written with the prefix of the word, sorted by name (case ignored, then as written);</li>
 *   <li>a name that starts with a dot is only matched by a pattern that starts with a dot;</li>
 *   <li>a word that matches nothing, or is not a valid pattern, is returned as written (a shell without
 *       {@code nullglob});</li>
 *   <li>a name that starts with {@code -} is written {@code ./-name}, so that the program does not take it for an
 *       option ({@code rm *} with a file called {@code -rf}).</li>
 * </ul>
 */
public final class GlobExpander {

    private static final Comparator<Path> BY_NAME = Comparator
            .comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT))
            .thenComparing(p -> p.getFileName().toString());

    /** A name found so far: how it is written in the result, and where it is. */
    private record Found(String text, Path path) {
    }

    private GlobExpander() {
    }

    /**
     * @param word             the word as written; relative to {@code currentDirectory} unless it starts with {@code /}
     * @param currentDirectory the folder a relative word starts from
     * @param ignoreCase       {@code true}: {@code *.TXT} matches {@code a.txt} (see {@link Globs#matcher(String, boolean)})
     * @return the names found, or the word itself if it has no wildcard or matches nothing
     */
    public static List<String> expand(String word, Path currentDirectory, boolean ignoreCase) {
        if (!Globs.hasWildcard(word)) {
            return List.of(word);
        }
        boolean absolute = word.startsWith("/");
        boolean directoriesOnly = word.endsWith("/");
        String path = word.substring(absolute ? 1 : 0, word.length() - (directoriesOnly ? 1 : 0));
        String[] segments = path.split("/", -1);

        List<Found> found = List.of(absolute
                ? new Found("/", currentDirectory.getRoot())
                : new Found("", currentDirectory));
        for (int i = 0; i < segments.length && !found.isEmpty(); i++) {
            boolean last = i == segments.length - 1;
            boolean directory = !last || directoriesOnly; // what is found here must be a folder
            try {
                found = Globs.hasWildcard(segments[i])
                        ? match(found, segments[i], directory, ignoreCase)
                        : literal(found, segments[i], directory);
            } catch (PatternSyntaxException _) {
                return List.of(word); // not a pattern: a shell passes it as written
            }
        }
        if (found.isEmpty()) {
            return List.of(word);
        }
        return found.stream().map(f -> f.text().startsWith("-") ? "./" + f.text() : f.text()).toList();
    }

    /** The folders found so far that hold an element called {@code name}. */
    private static List<Found> literal(List<Found> parents, String name, boolean directory) {
        List<Found> found = new ArrayList<>();
        for (Found parent : parents) {
            Path path = parent.path().resolve(name);
            boolean exists = directory ? Files.isDirectory(path) : Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            if (exists) {
                found.add(new Found(parent.text() + name + (directory ? "/" : ""), path));
            }
        }
        return found;
    }

    /** The elements of the folders found so far whose name matches {@code pattern}. */
    private static List<Found> match(List<Found> parents, String pattern, boolean directory, boolean ignoreCase) {
        PathMatcher matcher = Globs.matcher(pattern, ignoreCase);
        boolean dotted = pattern.startsWith(".");
        List<Found> found = new ArrayList<>();
        for (Found parent : parents) {
            if (!Files.isDirectory(parent.path())) {
                continue;
            }
            List<Path> names = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent.path())) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if ((dotted || !name.startsWith(".")) && matcher.matches(entry.getFileName())
                            && (!directory || Files.isDirectory(entry))) {
                        names.add(entry);
                    }
                }
            } catch (IOException | SecurityException _) {
                continue; // a folder that cannot be read matches nothing
            }
            names.sort(BY_NAME);
            for (Path entry : names) {
                found.add(new Found(parent.text() + entry.getFileName() + (directory ? "/" : ""), entry));
            }
        }
        return found;
    }
}
