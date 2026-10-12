package io.powerj.cmdlets;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.powerj.api.Display;
import io.powerj.api.Highlight;
import io.powerj.api.Span;

/**
 * Line of a file matching the pattern of {@code grep} (specification FR-64).
 *
 * @param path    absolute path of the file
 * @param line    line number, from 1
 * @param text    text of the line, without its end-of-line characters; the matches are highlighted in color
 * @param matches position of each match in {@code text} (empty with {@code --invert})
 * @param groups  capturing groups of the first match: {@code groups[0]} is the whole match, {@code groups[1]}
 *                the first group… ({@code null} for a group that did not participate)
 * @param named   named groups {@code (?<name>…)} of the first match: {@code m.named.version}
 */
@Display(columns = {"path", "line", "text"})
public record GrepMatch(Path path, int line, @Highlight("matches") String text, List<Span> matches,
                        List<String> groups, Map<String, String> named) {

    public GrepMatch {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(text, "text");
        matches = List.copyOf(matches);
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(named, "named");
    }
}
