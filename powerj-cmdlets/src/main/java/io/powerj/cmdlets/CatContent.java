package io.powerj.cmdlets;

import java.util.Objects;

import io.powerj.api.Display;

/**
 * Whole content read by {@code cat --mode content} (specification FR-65): the files one after the other.
 *
 * @param length  number of characters of {@code content}
 * @param content content of the files, end-of-line characters included
 */
@Display(columns = {"length", "content"})
public record CatContent(int length, String content) {

    public CatContent {
        Objects.requireNonNull(content, "content");
    }
}
