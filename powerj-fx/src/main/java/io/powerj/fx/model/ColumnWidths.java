package io.powerj.fx.model;

import java.util.List;

/** How wide a column should be, in characters. */
public final class ColumnWidths {

    static final int MIN = 4;
    static final int MAX = 60;

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
}
