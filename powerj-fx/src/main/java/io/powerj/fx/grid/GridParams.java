package io.powerj.fx.grid;

import io.powerj.api.Option;

/**
 * Options of {@code grid}.
 *
 * @param maxRows most rows shown, {@value #DEFAULT_MAX_ROWS} by default: the input is not read beyond
 * @param title   title of the window
 * @param columns the columns to show first, in this order, separated by commas: the others are hidden (the menu of the
 *                window shows them)
 * @param waitClose do not give the prompt back until the window is closed
 * @param passThru  rows can be ticked in the window, and the ones ticked when OK is pressed go on in the pipeline; it
 *                  waits for the window, as {@code --wait}
 * @param single    with {@code --pass-thru}, only one row can be ticked
 */
public record GridParams(
        @Option(longName = "max-rows", description = "Most rows shown (default 100000): the input is not read beyond")
        Integer maxRows,
        @Option(description = "Title of the window")
        String title,
        @Option(description = "Columns to show first, in this order, separated by commas; the others are hidden")
        String columns,
        @Option(longName = "wait", description = "Waits for the window to be closed before giving the prompt back")
        boolean waitClose,
        @Option(longName = "pass-thru", description = "Lets the user tick rows and gives them back to the pipeline (OK), "
                + "or nothing (Cancel); waits for the window")
        boolean passThru,
        @Option(description = "With --pass-thru: only one row can be ticked")
        boolean single) {

    public static final int DEFAULT_MAX_ROWS = 100_000;

    /** Options for a plain {@code grid}, with the limit of rows given. */
    public GridParams(Integer maxRows) {
        this(maxRows, null, null, false, false, false);
    }

    /** The limit of rows, the default if none was given. */
    public int limit() {
        return maxRows == null ? DEFAULT_MAX_ROWS : maxRows;
    }
}
