package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RowExporterTest {

    private static final List<ColumnSpec> COLUMNS = List.of(new ColumnSpec("name", ColumnKind.TEXT),
            new ColumnSpec("size", ColumnKind.SIZE), new ColumnSpec("modified", ColumnKind.DATE));

    private static GridRow row(Object name, Object size, Object modified) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("size", size);
        values.put("modified", modified);
        return new GridRow(values, values);
    }

    private final GridRow ada = row("Parser.java", 14_520L, Instant.parse("2026-10-05T18:12:30Z"));

    // --- tab-separated ---

    @Test
    void tsvIsAHeaderAndOneLinePerRowWithTheCellsAsShown() {
        String tsv = RowExporter.tsv(COLUMNS, List.of(ada, row("b.txt", 1_024L, null)));
        String[] lines = tsv.split("\n", -1);
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("name\tsize\tmodified");
        assertThat(lines[1]).startsWith("Parser.java\t14.2 KB\t2026-10-");
        assertThat(lines[2]).isEqualTo("b.txt\t1.0 KB\t");
    }

    @Test
    void tsvHasOnlyTheHeaderWithoutRows() {
        assertThat(RowExporter.tsv(COLUMNS, List.of())).isEqualTo("name\tsize\tmodified");
        assertThat(RowExporter.tsv(List.of(), List.of())).isEmpty();
    }

    @Test
    void aTabOrALineBreakInACellDoesNotMoveIt() {
        String tsv = RowExporter.tsv(List.of(COLUMNS.get(0)), List.of(row("a\tb\nc\r\nd\re", 0L, null)));
        assertThat(tsv).isEqualTo("name\na b c d e");
    }

    @Test
    void tsvKeepsALongTextWhole() {
        String longText = "x".repeat(CellFormatter.MAX_LENGTH + 100);
        assertThat(RowExporter.tsv(List.of(COLUMNS.get(0)), List.of(row(longText, 0L, null)))).endsWith(longText);
    }

    @Test
    void onlyTheColumnsGivenAreCopiedInTheirOrder() {
        String tsv = RowExporter.tsv(List.of(COLUMNS.get(1), COLUMNS.get(0)), List.of(ada));
        assertThat(tsv).isEqualTo("size\tname\n14.2 KB\tParser.java");
    }

    // --- JSON ---

    @Test
    void jsonKeepsTheTypesOfTheValues() {
        var values = new LinkedHashMap<String, Object>();
        values.put("text", "a");
        values.put("int", 3);
        values.put("long", 14_520L);
        values.put("double", 1.5);
        values.put("big", new BigDecimal("12345678901234567890.50"));
        values.put("bigint", new BigInteger("123456789012345678901234567890"));
        values.put("yes", true);
        values.put("none", null);
        List<ColumnSpec> columns = values.keySet().stream().map(n -> new ColumnSpec(n, ColumnKind.TEXT)).toList();
        assertThat(RowExporter.json(columns, List.of(new GridRow(values, values)))).isEqualTo("""
                [
                  {"text": "a", "int": 3, "long": 14520, "double": 1.5, "big": 12345678901234567890.50, \
                "bigint": 123456789012345678901234567890, "yes": true, "none": null}
                ]""");
    }

    @Test
    void aSizeIsItsBytesAndADateIsIso() {
        String json = RowExporter.json(COLUMNS, List.of(ada));
        assertThat(json).contains("\"size\": 14520", "\"modified\": \"2026-10-05T18:12:30Z\"");
        var others = new LinkedHashMap<String, Object>();
        others.put("d", new Date(0));
        others.put("l", LocalDate.of(2026, 10, 5));
        others.put("p", java.nio.file.Path.of("a"));
        List<ColumnSpec> columns = List.of(new ColumnSpec("d", ColumnKind.DATE), new ColumnSpec("l", ColumnKind.DATE),
                new ColumnSpec("p", ColumnKind.TEXT));
        assertThat(RowExporter.json(columns, List.of(new GridRow(others, others))))
                .contains("\"d\": \"1970-01-01T00:00:00Z\"", "\"l\": \"2026-10-05\"", "\"p\": \"a\"");
    }

    @Test
    void aNumberThatIsNotFiniteIsNull() {
        var values = new LinkedHashMap<String, Object>();
        values.put("a", Double.NaN);
        values.put("b", Float.POSITIVE_INFINITY);
        List<ColumnSpec> columns = List.of(new ColumnSpec("a", ColumnKind.NUMBER), new ColumnSpec("b", ColumnKind.NUMBER));
        assertThat(RowExporter.json(columns, List.of(new GridRow(values, values)))).contains("\"a\": null, \"b\": null");
    }

    @Test
    void textIsEscaped() {
        String json = RowExporter.json(List.of(COLUMNS.get(0)), List.of(row("say \"hi\"\\\n\t\r\u0001é", 0L, null)));
        assertThat(json).contains("\"say \\\"hi\\\"\\\\\\n\\t\\r\\u0001é\"");
        assertThat(RowExporter.json(List.of(new ColumnSpec("a\"b", ColumnKind.TEXT)), List.of(row("x", 0L, null))))
                .contains("{\"a\\\"b\": null}");
    }

    @Test
    void severalRowsAreOnePerLineAndNoRowIsAnEmptyArray() {
        String json = RowExporter.json(List.of(COLUMNS.get(0)), List.of(row("a", 0L, null), row("b", 0L, null)));
        assertThat(json).isEqualTo("[\n  {\"name\": \"a\"},\n  {\"name\": \"b\"}\n]");
        assertThat(RowExporter.json(COLUMNS, List.of())).isEqualTo("[]");
    }

    // --- one object in full ---

    @Test
    void detailsListEveryPropertyWithTheNamesAligned() {
        String details = RowExporter.details(COLUMNS, ada);
        assertThat(details.split("\n")).containsExactly("name     : Parser.java", "size     : 14.2 KB",
                details.split("\n")[2]);
        assertThat(details.split("\n")[2]).startsWith("modified : 2026-10-");
    }

    @Test
    void detailsKeepTheWholeTextAndItsLineBreaks() {
        String text = "line 1\nline 2 " + "y".repeat(CellFormatter.MAX_LENGTH + 10);
        String details = RowExporter.details(List.of(COLUMNS.get(0)), row(text, 0L, null));
        assertThat(details).isEqualTo("name : " + text);
        assertThat(RowExporter.details(List.of(), ada)).isEmpty();
    }
}
