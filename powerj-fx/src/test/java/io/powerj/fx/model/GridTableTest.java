package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GridTableTest {

    private record Item(String name, long size, String group) {
    }

    private final ObjectReader reader = new ObjectReader(Reflect::read);

    private GridModel model(Item... items) {
        var builder = new GridModel.Builder();
        for (Item item : items) {
            builder.add(reader.read(item));
        }
        return builder.build();
    }

    private static List<String> names(GridTable table) {
        return table.rows().stream().map(r -> (String) r.get("name")).toList();
    }

    private static SortSpec by(String column, SortDirection direction) {
        return new SortSpec(List.of(new SortKey(column, direction)));
    }

    private final GridModel items = model(new Item("b", 10_240, "x"), new Item("a", 9_216, "y"),
            new Item("c", 14_520, "x"), new Item("d", 9_216, "y"));

    @Test
    void theRowsStartInTheOrderOfArrival() {
        var table = new GridTable(items);
        assertThat(names(table)).containsExactly("b", "a", "c", "d");
        assertThat(table.sort()).isEqualTo(SortSpec.NONE);
        assertThat(table.columns()).isEqualTo(items.columns());
    }

    @Test
    void sortingReordersTheRows() {
        var table = new GridTable(items);
        table.sort(by("name", SortDirection.ASCENDING));
        assertThat(names(table)).containsExactly("a", "b", "c", "d");
        table.sort(by("name", SortDirection.DESCENDING));
        assertThat(names(table)).containsExactly("d", "c", "b", "a");
        assertThat(table.sort()).isEqualTo(by("name", SortDirection.DESCENDING));
    }

    @Test
    void aSizeIsSortedByItsBytes() {
        var table = new GridTable(items);
        table.sort(by("size", SortDirection.ASCENDING));
        assertThat(names(table).subList(2, 4)).containsExactly("b", "c"); // 10 KB then 14.2 KB
    }

    @Test
    void rowsThatAreEqualKeepTheirOrderOfArrival() {
        var table = new GridTable(items);
        table.sort(by("size", SortDirection.ASCENDING));
        assertThat(names(table)).containsExactly("a", "d", "b", "c"); // a and d are 9 KB: a came first
        table.sort(by("size", SortDirection.DESCENDING));
        assertThat(names(table)).containsExactly("c", "b", "a", "d");
        table.sort(by("group", SortDirection.ASCENDING));
        assertThat(names(table)).containsExactly("b", "c", "a", "d");
    }

    @Test
    void theSameSortGivesTheSameOrderWhateverCameBefore() {
        var table = new GridTable(items);
        table.sort(by("size", SortDirection.DESCENDING));
        table.sort(by("group", SortDirection.ASCENDING));
        List<String> afterAnotherSort = names(table);
        var fresh = new GridTable(items);
        fresh.sort(by("group", SortDirection.ASCENDING));
        assertThat(afterAnotherSort).isEqualTo(names(fresh)).containsExactly("b", "c", "a", "d");
    }

    @Test
    void severalKeys() {
        var table = new GridTable(items);
        table.sort(new SortSpec(List.of(new SortKey("group", SortDirection.DESCENDING),
                new SortKey("size", SortDirection.ASCENDING))));
        assertThat(names(table)).containsExactly("a", "d", "b", "c");
    }

    @Test
    void clearingTheSortBringsBackTheOrderOfArrival() {
        var table = new GridTable(items);
        table.sort(by("name", SortDirection.DESCENDING));
        table.sort(SortSpec.NONE);
        assertThat(names(table)).containsExactly("b", "a", "c", "d");
    }

    @Test
    void theModelIsNotReordered() {
        var table = new GridTable(items);
        table.sort(by("name", SortDirection.ASCENDING));
        assertThat(items.rows().stream().map(r -> r.get("name"))).containsExactly("b", "a", "c", "d");
    }

    @Test
    void theTableTellsWhenItsRowsMove() {
        var table = new GridTable(items);
        List<Object> changes = new ArrayList<>();
        table.rows().addListener((javafx.collections.ListChangeListener<GridRow>) changes::add);
        table.sort(by("name", SortDirection.ASCENDING));
        assertThat(changes).isNotEmpty();
    }

    @Test
    void anEmptyTableCanBeSorted() {
        var table = new GridTable(model());
        table.sort(by("name", SortDirection.ASCENDING));
        assertThat(table.rows()).isEmpty();
    }

    private FilterSpec filter(String column, String expression) {
        var editor = new FilterEditor(items.columns());
        return editor.setColumn(column, expression);
    }

    @Test
    void filteringKeepsTheMatchingRowsInTheCurrentOrder() {
        var table = new GridTable(items);
        table.filter(filter("group", "x"));
        assertThat(names(table)).containsExactly("b", "c");
        assertThat(table.total()).isEqualTo(4);
        table.sort(by("name", SortDirection.DESCENDING));
        assertThat(names(table)).containsExactly("c", "b");
        table.filter(filter("size", ">=10240"));
        assertThat(names(table)).containsExactly("c", "b");
        table.filter(filter("size", "<10240"));
        assertThat(names(table)).containsExactly("d", "a");
    }

    @Test
    void noFilterShowsEverythingAgainWithoutLosingTheSort() {
        var table = new GridTable(items);
        table.sort(by("name", SortDirection.ASCENDING));
        table.filter(filter("group", "y"));
        assertThat(names(table)).containsExactly("a", "d");
        table.filter(FilterSpec.NONE);
        assertThat(names(table)).containsExactly("a", "b", "c", "d");
        assertThat(table.rows()).hasSize(table.total());
    }

    @Test
    void theModelIsNotFiltered() {
        var table = new GridTable(items);
        table.filter(filter("name", "zzz"));
        assertThat(table.rows()).isEmpty();
        assertThat(items.rows()).hasSize(4);
        assertThat(table.total()).isEqualTo(4);
    }

    private final ObjectReader objects = new ObjectReader(Reflect::read);

    private record Other(String name, String extra) {
    }

    @Test
    void anEmptyTableGrowsWithWhatIsAppended() {
        var table = new GridTable();
        assertThat(table.total()).isZero();
        assertThat(table.columns()).isEmpty();
        table.append(List.of(objects.read(new Item("b", 10, "x")), objects.read(new Item("a", 5, "y"))));
        assertThat(names(table)).containsExactly("b", "a");
        assertThat(table.columns().stream().map(ColumnSpec::name)).containsExactly("name", "size", "group");
        table.append(List.of(objects.read(new Item("c", 7, "x"))));
        assertThat(names(table)).containsExactly("b", "a", "c");
        assertThat(table.total()).isEqualTo(3);
        assertThat(table.rows().stream().map(GridRow::position)).containsExactly(0, 1, 2);
    }

    @Test
    void theListenersOfTheColumnsSurviveACollection() {
        var table = new GridTable();
        List<Object> heard = new ArrayList<>();
        table.columns().addListener((javafx.collections.ListChangeListener<ColumnSpec>) heard::add);
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        table.append(List.of(objects.read(new Item("a", 1, "x"))));
        assertThat(heard).isNotEmpty();
        assertThat(table.columns()).isSameAs(table.columns());
    }

    @Test
    void newColumnsAreAnnouncedAfterTheirRows() {
        var table = new GridTable();
        List<String> events = new ArrayList<>();
        table.columns().addListener((javafx.collections.ListChangeListener<ColumnSpec>) change -> {
            while (change.next()) {
                events.add((change.wasReplaced() ? "replaced " : "added ") + change.getList().size()
                        + " with " + table.total() + " rows");
            }
        });
        table.append(List.of(objects.read(new Item("a", 1, "x"))));
        table.append(List.of(objects.read(new Item("b", 2, "y"))));             // nothing new
        table.append(List.of(objects.read(new Other("c", "e"))));               // one new column
        assertThat(events).containsExactly("added 3 with 1 rows", "added 4 with 3 rows");
        assertThat(table.columns().stream().map(ColumnSpec::name)).containsExactly("name", "size", "group", "extra");
    }

    @Test
    void aColumnWhoseKindBecomesClearerIsReplacedWhereItIs() {
        var table = new GridTable();
        java.util.Map<String, Object> empty = new java.util.HashMap<>();
        empty.put("v", null);
        table.append(List.of(objects.read(empty)));
        assertThat(table.columns()).containsExactly(new ColumnSpec("v", ColumnKind.UNKNOWN));
        List<String> events = new ArrayList<>();
        table.columns().addListener((javafx.collections.ListChangeListener<ColumnSpec>) change -> {
            while (change.next()) {
                events.add(change.wasReplaced() ? "replaced" : change.wasAdded() ? "added" : "other");
            }
        });
        table.append(List.of(objects.read(Map.of("v", 5))));
        assertThat(table.columns()).containsExactly(new ColumnSpec("v", ColumnKind.NUMBER));
        assertThat(events).containsExactly("replaced");
        table.append(List.of(objects.read(Map.of("v", 6))));
        assertThat(events).hasSize(1); // the kind did not change again
    }

    @Test
    void theSortAndTheFilterApplyToTheRowsThatArriveLater() {
        var table = new GridTable();
        table.append(List.of(objects.read(new Item("b", 10, "x")), objects.read(new Item("d", 20, "y"))));
        table.sort(by("name", SortDirection.DESCENDING));
        table.filter(new FilterEditor(table.columns()).setColumn("group", "x"));
        assertThat(names(table)).containsExactly("b");
        table.append(List.of(objects.read(new Item("c", 30, "x")), objects.read(new Item("e", 40, "y")),
                objects.read(new Item("a", 50, "x"))));
        assertThat(names(table)).containsExactly("c", "b", "a");
        assertThat(table.total()).isEqualTo(5);
    }

    @Test
    void theArgumentsAreMandatory() {
        assertThatThrownBy(() -> new GridTable(items).filter(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GridTable(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GridTable(items).sort(null)).isInstanceOf(NullPointerException.class);
    }
}
