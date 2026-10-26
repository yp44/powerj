package io.powerj.fx.model;

import java.util.List;

/** How wide a column should be, in characters. */
public final class ColumnWidths {

    static final int MIN = 4;
    static final int MAX = 60;
    /** A character is about this wide, in pixels; a column also has its padding. */
    static final double CHAR_WIDTH = 7.5;
    static final double PADDING = 28;
    /** Width of a numeric column: the list of comparisons, and room to type a value. */
    static final double MIN_FILTERED_NUMBER = 150;

    private ColumnWidths() {
    }

    /**
     * The widest of the header and of the cells of the first {@code sample} rows, between {@value #MIN} and
     * {@value #MAX} characters: reading every row of a large table would cost more than it helps.
     */
    public static int chars(ColumnSpec column, List<GridRow> rows, int sample) {
        int width = column.name().length();
        for (int i = 0; i < Math.min(sample, rows.size()); i++) {
            width = Math.max(width, CellFormatter.text(rows.get(i).get(column.name()), column.kind()).length());
        }
        return Math.clamp(width, MIN, MAX);
    }

    /** The width in pixels: the characters of {@link #chars}, the padding, and room for the filter of a numeric column. */
    public static double pixels(ColumnSpec column, List<GridRow> rows, int sample) {
        double width = chars(column, rows, sample) * CHAR_WIDTH + PADDING;
        return column.kind().hasComparisons() ? Math.max(width, MIN_FILTERED_NUMBER) : width;
    }
}
