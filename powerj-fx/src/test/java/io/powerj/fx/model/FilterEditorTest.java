package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class FilterEditorTest {

    private record File(String name, long size, Instant modified, String owner) {
    }

    private static final List<ColumnSpec> COLUMNS = List.of(new ColumnSpec("name", ColumnKind.TEXT),
            new ColumnSpec("size", ColumnKind.SIZE), new ColumnSpec("modified", ColumnKind.DATE),
            new ColumnSpec("owner", ColumnKind.TEXT));

    private final FilterEditor editor = new FilterEditor(COLUMNS, new FilterParser(ZoneId.of("UTC")));
    private final ObjectReader reader = new ObjectReader(Reflect::read);

    private final List<GridRow> rows = List.of(
            row("Parser.java", 14_520, "2026-10-05T18:12:00Z", "ada"),
            row("ParserTest.java", 9_216, "2026-10-06T08:00:00Z", "alan"),
            row("README.md", 400, "2025-01-01T00:00:00Z", "ada"),
            row("notes.txt", 12_288, "2026-03-01T00:00:00Z", null));

    private GridRow row(String name, long size, String modified, String owner) {
        return reader.read(new File(name, size, Instant.parse(modified), owner)).row();
    }

    private List<String> shown() {
        Predicate<GridRow> predicate = editor.spec().predicate();
        return rows.stream().filter(predicate == null ? r -> true : predicate).map(r -> (String) r.get("name")).toList();
    }

    @Test
    void withoutFilterEverythingIsShown() {
        assertThat(editor.spec()).isSameAs(FilterSpec.NONE);
        assertThat(editor.spec().isEmpty()).isTrue();
        assertThat(editor.spec().predicate()).isNull();
        assertThat(shown()).hasSize(4);
    }

    @Test
    void aColumnFilterKeepsTheMatchingRows() {
        editor.setColumn("name", "parser");
        assertThat(shown()).containsExactly("Parser.java", "ParserTest.java");
        assertThat(editor.spec().isEmpty()).isFalse();
    }

    @Test
    void theFiltersOfSeveralColumnsAreCombined() {
        editor.setColumn("name", "parser");
        editor.setColumn("size", ">10kb");
        assertThat(shown()).containsExactly("Parser.java");
        editor.setColumn("size", "");
        assertThat(shown()).containsExactly("Parser.java", "ParserTest.java");
    }

    @Test
    void aFilterUsesTheKindOfItsColumn() {
        editor.setColumn("size", "<10kb");
        assertThat(shown()).containsExactly("ParserTest.java", "README.md");
        editor.clear();
        editor.setColumn("modified", "2026-10");
        assertThat(shown()).containsExactly("Parser.java", "ParserTest.java");
        editor.setColumn("modified", ">=2026-10");
        assertThat(shown()).containsExactly("Parser.java", "ParserTest.java");
        editor.setColumn("modified", "2026-03..2026-10-05");
        assertThat(shown()).containsExactly("Parser.java", "notes.txt");
    }

    @Test
    void anEmptyCellCanBeFound() {
        editor.setColumn("owner", "=");
        assertThat(shown()).containsExactly("notes.txt");
        editor.setColumn("owner", "!a");
        assertThat(shown()).containsExactly("notes.txt");
    }

    @Test
    void anInvalidExpressionKeepsTheLastGoodFilterAndIsReported() {
        editor.setColumn("size", ">10kb");
        assertThat(shown()).containsExactly("Parser.java", "notes.txt");
        editor.setColumn("size", ">10xb");
        assertThat(editor.error("size")).contains("not a size (4096, 10kb, 1.5mb, 2gb…): 10xb");
        assertThat(shown()).containsExactly("Parser.java", "notes.txt");
        editor.setColumn("size", ">1kb");
        assertThat(editor.error("size")).isEmpty();
        assertThat(shown()).containsExactly("Parser.java", "ParserTest.java", "notes.txt");
    }

    @Test
    void anInvalidFirstExpressionFiltersNothing() {
        editor.setColumn("name", "/(/");
        assertThat(editor.error("name")).isPresent();
        assertThat(shown()).hasSize(4);
    }

    @Test
    void anEmptyExpressionRemovesTheFilterAndTheError() {
        editor.setColumn("name", "/(/");
        editor.setColumn("name", "");
        assertThat(editor.error("name")).isEmpty();
        editor.setColumn("name", "readme");
        editor.setColumn("name", "  ");
        assertThat(shown()).hasSize(4);
    }

    @Test
    void anUnknownColumnIsIgnored() {
        editor.setColumn("nothing", "x");
        assertThat(editor.spec().isEmpty()).isTrue();
        assertThat(editor.error("nothing")).isEmpty();
    }

    @Test
    void theSearchLooksInEveryColumn() {
        editor.setSearch("ada");
        assertThat(shown()).containsExactly("Parser.java", "README.md");
        editor.setSearch("KB");
        assertThat(shown()).containsExactly("Parser.java", "ParserTest.java", "notes.txt");
        editor.setSearch("2025");
        assertThat(shown()).containsExactly("README.md");
    }

    @Test
    void theSearchAndTheColumnFiltersAreCombined() {
        editor.setSearch("ada");
        editor.setColumn("size", ">1kb");
        assertThat(shown()).containsExactly("Parser.java");
    }

    @Test
    void theSearchCanBeNegatedAndMeansNoColumnHasIt() {
        editor.setSearch("!ada");
        assertThat(shown()).containsExactly("ParserTest.java", "notes.txt");
        editor.setSearch("!parser");
        assertThat(shown()).containsExactly("README.md", "notes.txt");
    }

    @Test
    void theSearchCanBeARegex() {
        editor.setSearch("/^readme/");
        assertThat(shown()).containsExactly("README.md");
    }

    @Test
    void anInvalidSearchKeepsTheLastGoodOne() {
        editor.setSearch("ada");
        editor.setSearch("/(/");
        assertThat(editor.searchError()).isPresent();
        assertThat(shown()).containsExactly("Parser.java", "README.md");
        editor.setSearch("alan");
        assertThat(editor.searchError()).isEmpty();
        assertThat(shown()).containsExactly("ParserTest.java");
        editor.setSearch("");
        assertThat(shown()).hasSize(4);
    }

    @Test
    void aSearchThatFiltersNothingKeepsEverything() {
        editor.setSearch("!");
        assertThat(editor.spec().isEmpty()).isTrue();
    }

    @Test
    void clearRemovesEverything() {
        editor.setColumn("name", "parser");
        editor.setColumn("size", "!!!oops>");
        editor.setSearch("/(/");
        editor.clear();
        assertThat(editor.spec().isEmpty()).isTrue();
        assertThat(editor.error("size")).isEmpty();
        assertThat(editor.searchError()).isEmpty();
        assertThat(shown()).hasSize(4);
    }

    @Test
    void theSystemParserIsTheDefault() {
        var system = new FilterEditor(COLUMNS);
        system.setColumn("name", "parser");
        assertThat(system.spec().isEmpty()).isFalse();
        assertThat(Map.of("a", 1)).isNotEmpty();
    }
}
