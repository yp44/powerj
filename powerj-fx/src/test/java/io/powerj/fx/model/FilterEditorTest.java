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
            row("README.md", 400, "2025-06-15T12:00:00Z", "ada"),
            row("notes.txt", 12_288, "2026-03-01T12:00:00Z", null));

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
    void aTypedColumnIsNotSearchedAsText() {
        editor.setColumn("size", "400");
        assertThat(shown()).containsExactly("README.md");
        editor.setColumn("size", "4");          // 4 bytes, not the digits of 400, 14520…
        assertThat(shown()).isEmpty();
        editor.setColumn("size", "9kb");        // shown as 9.0 KB
        assertThat(shown()).containsExactly("ParserTest.java");
        editor.setColumn("size", "KB");         // not a size: the last good filter stays
        assertThat(editor.error("size")).isPresent();
        assertThat(shown()).containsExactly("ParserTest.java");
    }

    @Test
    void anIntegerColumnFindsTheValueAndNotItsDigits() {
        var sizes = new FilterEditor(List.of(new ColumnSpec("n", ColumnKind.NUMBER)));
        List<GridRow> numbers = new java.util.ArrayList<>();
        for (long n : new long[] {228, 2_280, 12_288, 3_280, 1_228, 5}) {
            numbers.add(reader.read(Map.of("n", n)).row());
        }
        sizes.setColumn("n", "228");
        assertThat(numbers.stream().filter(sizes.spec().predicate()).map(r -> r.get("n"))).containsExactly(228L);
        sizes.setColumn("n", ">=2000");
        assertThat(numbers.stream().filter(sizes.spec().predicate()).map(r -> r.get("n")))
                .containsExactly(2_280L, 12_288L, 3_280L);
    }

    @Test
    void theOperatorOfTheListGoesWithAPlainValue() {
        editor.setColumn("size", FilterOperator.GREATER, "10kb");
        assertThat(shown()).containsExactly("Parser.java", "notes.txt");
        editor.setColumn("size", FilterOperator.LESS_OR_EQUAL, "9kb");
        assertThat(shown()).containsExactly("ParserTest.java", "README.md");
        editor.setColumn("size", FilterOperator.LESS, "9kb");
        assertThat(shown()).containsExactly("README.md");
        editor.setColumn("size", FilterOperator.GREATER_OR_EQUAL, "9kb");
        assertThat(shown()).containsExactly("Parser.java", "ParserTest.java", "notes.txt");
        editor.setColumn("size", FilterOperator.EQUAL, "400b");
        assertThat(shown()).containsExactly("README.md");
        editor.setColumn("size", FilterOperator.LESS, "");
        assertThat(shown()).hasSize(4);
    }

    @Test
    void aTextWithItsOwnOperatorIgnoresTheList() {
        editor.setColumn("size", FilterOperator.LESS, ">10kb");
        assertThat(shown()).containsExactly("Parser.java", "notes.txt");
        editor.setColumn("size", FilterOperator.LESS, "1kb..10kb");
        assertThat(shown()).containsExactly("ParserTest.java");
        editor.setColumn("size", FilterOperator.GREATER, "/^4/");
        assertThat(shown()).containsExactly("README.md");
    }

    @Test
    void theOperatorAlsoGoesWithDates() {
        editor.setColumn("modified", FilterOperator.GREATER_OR_EQUAL, "2026-10-06");
        assertThat(shown()).containsExactly("ParserTest.java");
        editor.setColumn("modified", FilterOperator.LESS, "2026");
        assertThat(shown()).containsExactly("README.md");
    }

    @Test
    void aTextColumnIgnoresTheList() {
        editor.setColumn("name", FilterOperator.GREATER, "read");
        assertThat(shown()).containsExactly("README.md");
        editor.setColumn("name", FilterOperator.LESS, ">me");
        assertThat(shown()).isEmpty(); // text: ">me" is looked for as it is typed
    }

    @Test
    void aBadValueWithAnOperatorIsReportedAsBefore() {
        editor.setColumn("size", FilterOperator.GREATER, "abc");
        assertThat(editor.error("size")).contains("not a size (4096, 10kb, 1.5mb, 2gb…): abc");
    }

    @Test
    void theSearchReachesTheColumnsThatArriveLater() {
        var late = new FilterEditor(List.of(new ColumnSpec("name", ColumnKind.TEXT)),
                new FilterParser(ZoneId.of("UTC")));
        late.setSearch("ada");
        GridRow row = reader.read(new File("x", 1, Instant.EPOCH, "ada")).row();
        assertThat(late.spec().predicate().test(row)).isFalse(); // the owner column is not known yet
        late.columns(COLUMNS);
        assertThat(late.spec().predicate().test(row)).isTrue();
        assertThat(late.columns(COLUMNS)).isSameAs(late.spec()); // nothing changed: nothing recomputed
    }

    @Test
    void clearingForgetsTheSearchForTheColumnsToo() {
        editor.setSearch("ada");
        editor.clear();
        editor.columns(List.of(COLUMNS.get(0)));
        assertThat(editor.spec().isEmpty()).isTrue();
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
