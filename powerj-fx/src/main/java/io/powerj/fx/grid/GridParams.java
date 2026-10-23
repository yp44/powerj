package io.powerj.fx.grid;

import io.powerj.api.Option;

/**
 * Options of {@code grid}.
 *
 * @param maxRows most rows shown, {@value #DEFAULT_MAX_ROWS} by default: the input is not read beyond
 */
public record GridParams(
        @Option(longName = "max-rows", description = "Most rows shown (default 100000): the input is not read beyond")
        Integer maxRows) {

    public static final int DEFAULT_MAX_ROWS = 100_000;

    /** The limit of rows, the default if none was given. */
    public int limit() {
        return maxRows == null ? DEFAULT_MAX_ROWS : maxRows;
    }
}
