package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ColumnWidthsTest {

    private static final ColumnSpec NAME = new ColumnSpec("name", ColumnKind.TEXT);

    private static List<GridRow> rows(String... names) {
        List<GridRow> rows = new ArrayList<>();
        for (String name : names) {
            rows.add(new GridRow(name, Map.of("name", name)));
        }
        return rows;
    }

    @Test
    void theHeaderCanBeTheWidest() {
        assertThat(ColumnWidths.chars(new ColumnSpec("modified", ColumnKind.TEXT), rows("a", "bb"), 10)).isEqualTo(8);
    }

    @Test
    void aCellCanBeTheWidest() {
        assertThat(ColumnWidths.chars(NAME, rows("a", "Parser.java", "b"), 10)).isEqualTo(11);
    }

    @Test
    void theWidthIsBetweenTheLimits() {
        assertThat(ColumnWidths.chars(new ColumnSpec("n", ColumnKind.TEXT), rows("a"), 10)).isEqualTo(ColumnWidths.MIN);
        assertThat(ColumnWidths.chars(NAME, rows("x".repeat(200)), 10)).isEqualTo(ColumnWidths.MAX);
    }

    @Test
    void onlyTheSampleIsRead() {
        assertThat(ColumnWidths.chars(NAME, rows("ab", "abcdefgh"), 1)).isEqualTo(4);
        assertThat(ColumnWidths.chars(NAME, rows("ab", "abcdefgh"), 2)).isEqualTo(8);
    }

    @Test
    void noRowsGivesTheHeader() {
        assertThat(ColumnWidths.chars(new ColumnSpec("description", ColumnKind.TEXT), List.of(), 10)).isEqualTo(11);
    }

    @Test
    void sizesAreMeasuredAsTheyAreShown() {
        var size = new ColumnSpec("s", ColumnKind.SIZE);
        var rows = List.of(new GridRow(1, Map.of("s", 14_520L)));
        assertThat(ColumnWidths.chars(size, rows, 10)).isEqualTo("14.2 KB".length());
    }
}
