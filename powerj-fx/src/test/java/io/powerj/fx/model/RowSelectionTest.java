package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RowSelectionTest {

    private final List<GridRow> rows = new ArrayList<>();

    private GridRow row(String name) {
        var row = new GridRow(name, Map.of("name", name), rows.size());
        rows.add(row);
        return row;
    }

    private static List<Object> sources(List<GridRow> rows) {
        return rows.stream().map(GridRow::source).toList();
    }

    @Test
    void nothingIsTickedAtFirst() {
        var selection = new RowSelection(false);
        GridRow a = row("a");
        assertThat(selection.isSelected(a)).isFalse();
        assertThat(selection.property(a).get()).isFalse();
        assertThat(selection.count().get()).isZero();
        assertThat(selection.selected(null)).isEmpty();
        assertThat(selection.isSingle()).isFalse();
    }

    @Test
    void tickingARowCountsIt() {
        var selection = new RowSelection(false);
        GridRow a = row("a");
        GridRow b = row("b");
        selection.property(a).set(true);
        selection.property(b).set(true);
        assertThat(selection.count().get()).isEqualTo(2);
        assertThat(selection.isSelected(a)).isTrue();
        selection.property(a).set(false);
        assertThat(selection.count().get()).isEqualTo(1);
        assertThat(sources(selection.selected(null))).containsExactly("b");
        assertThat(selection.property(a)).isSameAs(selection.property(a)); // one tick per row, for its check box
    }

    @Test
    void rowsAreToldApartByIdentityNotByContent() {
        var selection = new RowSelection(false);
        GridRow first = new GridRow("same", Map.of("name", "same"), 0);
        GridRow second = new GridRow("same", Map.of("name", "same"), 0); // equal as a record, another row
        assertThat(first).isEqualTo(second);
        selection.property(first).set(true);
        assertThat(selection.isSelected(first)).isTrue();
        assertThat(selection.isSelected(second)).isFalse();
        assertThat(selection.count().get()).isEqualTo(1);
    }

    @Test
    void selectAllTicksTheRowsGivenAndOnlyThem() {
        var selection = new RowSelection(false);
        GridRow a = row("a");
        GridRow b = row("b");
        GridRow c = row("c");
        GridRow d = row("d");
        selection.property(d).set(true);                 // ticked before, not among the rows found by the filter
        selection.selectAll(List.of(a, b));
        assertThat(sources(selection.selected(null))).containsExactly("a", "b", "d");
        assertThat(selection.isSelected(c)).isFalse();
        assertThat(selection.count().get()).isEqualTo(3);
        selection.selectAll(List.of(a, b));              // again: nothing changes
        assertThat(selection.count().get()).isEqualTo(3);
    }

    @Test
    void unselectAllUnticksTheRowsGivenAndOnlyThem() {
        var selection = new RowSelection(false);
        GridRow a = row("a");
        GridRow b = row("b");
        GridRow c = row("c");
        selection.selectAll(List.of(a, b, c));
        selection.unselectAll(List.of(a, b));
        assertThat(sources(selection.selected(null))).containsExactly("c");   // c was hidden by the filter: still ticked
        assertThat(selection.count().get()).isEqualTo(1);
        selection.unselectAll(List.of(row("never seen")));                      // a row that was never ticked
        assertThat(selection.count().get()).isEqualTo(1);
        selection.unselectAll(List.of());
        assertThat(selection.count().get()).isEqualTo(1);
    }

    @Test
    void theSelectionComesInTheOrderOfTheSortOrOfArrival() {
        var selection = new RowSelection(false);
        GridRow b = row("b");
        GridRow c = row("c");
        GridRow a = row("a");
        selection.selectAll(List.of(a, b, c));
        assertThat(sources(selection.selected(null))).containsExactly("b", "c", "a");          // arrival
        Comparator<GridRow> byName = Comparator.comparing(r -> (String) r.get("name"));
        assertThat(sources(selection.selected(byName))).containsExactly("a", "b", "c");
        assertThat(sources(selection.selected(byName.reversed()))).containsExactly("c", "b", "a");
    }

    @Test
    void inSingleModeTickingARowUnticksTheOther() {
        var selection = new RowSelection(true);
        GridRow a = row("a");
        GridRow b = row("b");
        selection.property(a).set(true);
        selection.property(b).set(true);
        assertThat(selection.isSelected(a)).isFalse();
        assertThat(selection.isSelected(b)).isTrue();
        assertThat(selection.count().get()).isEqualTo(1);
        selection.property(b).set(false);
        assertThat(selection.count().get()).isZero();
        assertThat(selection.isSingle()).isTrue();
    }

    @Test
    void inSingleModeSelectAllDoesNothingButUnselectAllWorks() {
        var selection = new RowSelection(true);
        GridRow a = row("a");
        GridRow b = row("b");
        selection.selectAll(List.of(a, b));
        assertThat(selection.count().get()).isZero();
        selection.property(a).set(true);
        selection.unselectAll(List.of(a, b));
        assertThat(selection.count().get()).isZero();
    }
}
