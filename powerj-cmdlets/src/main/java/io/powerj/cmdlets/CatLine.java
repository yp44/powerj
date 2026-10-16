package io.powerj.cmdlets;

import java.nio.file.Path;
import java.util.Objects;

import io.powerj.api.Display;

/**
 * Line of a file read by {@code cat --numbered} (specification FR-65).
 *
 * @param path   absolute path of the file (not displayed by default: {@code l.path})
 * @param line   line number, from 1; it goes on from one file to the next
 * @param length number of characters of {@code text}
 * @param text   text of the line, without its end-of-line characters
 */
@Display(columns = {"line", "length", "text"})
public record CatLine(Path path, int line, int length, String text) {

    public CatLine {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(text, "text");
    }
}
