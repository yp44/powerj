package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SortSpecTest {

    private static final String COLUMN = "v";

    private static List<GridRow> rows(Object... values) {
        List<GridRow> rows = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put(COLUMN, value);
            rows.add(new GridRow(value, map));
        }
        return rows;
    }

    private static SortSpec by(SortDirection direction) {
        return new SortSpec(List.of(new SortKey(COLUMN, direction)));
    }

    /** The values of the column once sorted. */
    private static List<Object> sorted(SortDirection direction, Object... values) {
        List<GridRow> rows = rows(values);
        rows.sort(by(direction).comparator());
        return rows.stream().map(r -> r.get(COLUMN)).toList();
    }

    private static List<Object> ascending(Object... values) {
        return sorted(SortDirection.ASCENDING, values);
    }

    @Test
    void numbersAreSortedByValueNotByText() {
        assertThat(ascending(10, 9, 100, 2)).containsExactly(2, 9, 10, 100);
        assertThat(ascending(10L, 9, (short) 8, (byte) 7)).containsExactly((byte) 7, (short) 8, 9, 10L);
        assertThat(ascending(2.5, 2, 3L, 1.5f)).containsExactly(1.5f, 2, 2.5, 3L);
        assertThat(ascending(-1, -10, 0)).containsExactly(-10, -1, 0);
    }

    @Test
    void sizesAreSortedByTheirBytes() {
        // 9 KB, 10 KB, 14.2 KB, 1 MB: the text "10.0 KB" would come before "9.0 KB".
        assertThat(ascending(10_240L, 9_216L, 14_520L, 1_048_576L)).containsExactly(9_216L, 10_240L, 14_520L, 1_048_576L);
    }

    @Test
    void bigNumbersAreSortedExactly() {
        BigInteger huge = new BigInteger("123456789012345678901234567890");
        assertThat(ascending(huge.add(BigInteger.ONE), huge, 5L)).containsExactly(5L, huge, huge.add(BigInteger.ONE));
        assertThat(ascending(new BigDecimal("1.10"), new BigDecimal("1.2"), 1L)).containsExactly(1L, new BigDecimal("1.10"),
                new BigDecimal("1.2"));
        assertThat(ascending(huge, 1e40, 7.5)).containsExactly(7.5, huge, 1e40);
        // A number that is not finite does not fail the sort.
        assertThat(ascending(Double.POSITIVE_INFINITY, huge, 3.0)).containsExactly(3.0, huge, Double.POSITIVE_INFINITY);
        assertThat(ascending(Double.NaN, 1.0, Double.NEGATIVE_INFINITY, new BigDecimal("2")))
                .containsExactly(Double.NEGATIVE_INFINITY, 1.0, new BigDecimal("2"), Double.NaN);
    }

    @Test
    void textsAreSortedWithoutRegardToCase() {
        assertThat(ascending("banana", "Apple", "cherry", "apple", "Banana"))
                .containsExactly("Apple", "apple", "Banana", "banana", "cherry");
        assertThat(ascending(new StringBuilder("b"), "a", 'c')).extracting(Object::toString).containsExactly("a", "b", "c");
    }

    @Test
    void datesAreSortedByTime() {
        Instant early = Instant.parse("2025-01-01T00:00:00Z");
        Instant late = Instant.parse("2026-06-01T00:00:00Z");
        assertThat(ascending(late, early)).containsExactly(early, late);
        // Different kinds of dates are compared as instants.
        Date date = Date.from(Instant.parse("2025-06-01T00:00:00Z"));
        var zoned = Instant.parse("2025-09-01T00:00:00Z").atZone(ZoneOffset.UTC);
        var offset = Instant.parse("2025-03-01T00:00:00Z").atOffset(ZoneOffset.UTC);
        assertThat(ascending(late, zoned, date, offset, early)).containsExactly(early, offset, date, zoned, late);
        assertThat(ascending(LocalDate.of(2026, 3, 1), LocalDate.of(2025, 3, 1)))
                .containsExactly(LocalDate.of(2025, 3, 1), LocalDate.of(2026, 3, 1));
        // Dates that cannot be compared as instants are sorted by text, without failing.
        assertThat(ascending(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDate.of(2025, 1, 1))).hasSize(2);
    }

    @Test
    void booleansHaveFalseFirst() {
        assertThat(ascending(true, false, true, false)).containsExactly(false, false, true, true);
    }

    @Test
    void otherValuesUseTheirNaturalOrderOrTheirText() {
        assertThat(ascending(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
        assertThat(ascending(Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(1)))
                .containsExactly(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofHours(1));
        UUID low = new UUID(0, 1);
        UUID high = new UUID(0, 2);
        assertThat(ascending(high, low)).containsExactly(low, high);
        assertThat(ascending(Path.of("b", "x"), Path.of("a", "y"))).containsExactly(Path.of("a", "y"), Path.of("b", "x"));
        assertThat(ascending(new Object[] {List.of(2), List.of(1)}).stream().map(Object::toString))
                .containsExactly("[1]", "[2]");
    }

    @Test
    void aColumnWithSeveralKindsShowsNumbersThenDatesThenBooleansThenTexts() {
        Instant instant = Instant.parse("2025-01-01T00:00:00Z");
        assertThat(ascending("b", true, instant, 3, "a", 1.5)).containsExactly(1.5, 3, instant, true, "a", "b");
    }

    @Test
    void nullsAreAlwaysLast() {
        assertThat(sorted(SortDirection.ASCENDING, 2, null, 1, null)).containsExactly(1, 2, null, null);
        assertThat(sorted(SortDirection.DESCENDING, 2, null, 1, null)).containsExactly(2, 1, null, null);
    }

    @Test
    void descendingReversesTheOrder() {
        assertThat(sorted(SortDirection.DESCENDING, 10, 9, 100, 2)).containsExactly(100, 10, 9, 2);
        assertThat(sorted(SortDirection.DESCENDING, "a", "C", "b")).containsExactly("C", "b", "a");
    }

    @Test
    void severalKeysBreakTheTiesOfTheFirst() {
        List<GridRow> rows = new ArrayList<>();
        for (Object[] pair : new Object[][] {{"b", 2}, {"a", 2}, {"c", 1}, {"a", 1}}) {
            rows.add(new GridRow(pair, Map.of("name", pair[0], "n", pair[1])));
        }
        var spec = new SortSpec(List.of(new SortKey("n", SortDirection.DESCENDING),
                new SortKey("name", SortDirection.ASCENDING)));
        rows.sort(spec.comparator());
        assertThat(rows.stream().map(r -> r.get("n") + "" + r.get("name"))).containsExactly("2a", "2b", "1a", "1c");
    }

    @Test
    void aColumnTheRowsDoNotHaveIsEmptyForAll() {
        List<GridRow> rows = rows("b", "a");
        rows.sort(new SortSpec(List.of(new SortKey("missing", SortDirection.ASCENDING))).comparator());
        assertThat(rows.stream().map(GridRow::source)).containsExactly("b", "a");
    }

    @Test
    void noKeyMeansTheOrderOfArrival() {
        assertThat(SortSpec.NONE.comparator()).isNull();
        assertThat(SortSpec.NONE.isEmpty()).isTrue();
        assertThat(by(SortDirection.ASCENDING).isEmpty()).isFalse();
    }

    @Test
    void aColumnCannotBeSortedTwice() {
        assertThatThrownBy(() -> new SortSpec(List.of(new SortKey("a", SortDirection.ASCENDING),
                new SortKey("a", SortDirection.DESCENDING)))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
    }

    @Test
    void aSpecDoesNotChange() {
        List<SortKey> keys = new ArrayList<>(List.of(new SortKey("a", SortDirection.ASCENDING)));
        var spec = new SortSpec(keys);
        keys.clear();
        assertThat(spec.keys()).hasSize(1);
        assertThatThrownBy(() -> spec.keys().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new SortKey(null, SortDirection.ASCENDING)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SortKey("a", null)).isInstanceOf(NullPointerException.class);
    }
}
