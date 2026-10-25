package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Comparison of {@code sort}, and the CSV and JSON text of the values (FR-67, FR-73). */
class ValueFormatsTest {

    enum Size { SMALL, LARGE }

    private final List<String> errors = new ArrayList<>();

    @Test
    void nullsComeFirst() {
        var order = new SortComparator(false, errors::add);
        assertThat(order.compare(null, null)).isZero();
        assertThat(order.compare(null, 1)).isNegative();
        assertThat(order.compare(1, null)).isPositive();
    }

    @Test
    void numbersOfEveryKindByValue() {
        assertThat(SortComparator.compareNumbers((short) 1, (byte) 2)).isNegative();
        assertThat(SortComparator.compareNumbers(1.5f, 1.25)).isPositive();
        assertThat(SortComparator.compareNumbers(Double.NaN, 1)).isPositive();
        assertThat(SortComparator.compareNumbers(1, Float.NaN)).isNegative();
        assertThat(SortComparator.compareNumbers(Double.NEGATIVE_INFINITY, new BigDecimal("-1e300"))).isNegative();
        assertThat(SortComparator.compareNumbers(new BigInteger("100000000000000000000"), 1e19)).isPositive();
        assertThat(SortComparator.compareNumbers(new BigDecimal("2.50"), 2.5f)).isZero();
        assertThat(SortComparator.compareNumbers(new java.util.concurrent.atomic.AtomicLong(3), 2)).isPositive();
    }

    @Test
    void comparablesOfOneClassByValueAndOtherKindsByTypeRank() {
        var order = new SortComparator(true, errors::add);
        assertThat(order.compare("a", "B")).isNegative();
        assertThat(order.compare(Instant.EPOCH, Instant.EPOCH.plusSeconds(1))).isNegative();
        assertThat(order.compare(Size.LARGE, Size.SMALL)).isPositive();
        assertThat(order.compare(Size.SMALL, "a")).isPositive(); // a comparable of another class comes after the strings
        assertThat(order.compare(List.of(1), "z")).isPositive(); // and what is not comparable comes last
        assertThat(errors).containsExactly(
                "cannot compare Size with String: values of different types are sorted by type (null, numbers, strings, the rest)");
    }

    @Test
    void csvTextOfFloatsEnumsAndOptionals() {
        assertThat(CsvWriter.text(2.50f)).isEqualTo("2.5");
        assertThat(CsvWriter.text(Float.NaN)).isEqualTo("NaN");
        assertThat(CsvWriter.text(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
        assertThat(CsvWriter.text(Size.LARGE)).isEqualTo("LARGE");
        assertThat(CsvWriter.text(Optional.of("x"))).isEqualTo("x");
        assertThat(CsvWriter.text(Optional.empty())).isEmpty();
        assertThat(new CsvWriter(',').line(List.of("end ", "", "a\rb"))).isEqualTo("\"end \",,\"a\rb\"");
    }

    @Test
    void csvDelimiters() {
        assertThat(CsvWriter.delimiter("\\t")).isEqualTo('\t');
        assertThat(CsvWriter.delimiter("TAB")).isEqualTo('\t');
        assertThatThrownBy(() -> CsvWriter.delimiter("\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsvWriter.delimiter("\r")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsvWriter.delimiter("")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String json(Object value) {
        StringBuilder out = new StringBuilder();
        new JsonWriter(false, 10).write(value, out, 0);
        return out.toString();
    }

    @Test
    void jsonOfSmallIntegersIteratorsAndControlCharacters() {
        assertThat(json((short) 3)).isEqualTo("3");
        assertThat(json((byte) -1)).isEqualTo("-1");
        assertThat(json(List.of(1, 2).iterator())).isEqualTo("[1,2]");
        assertThat(json(new int[] {4, 5})).isEqualTo("[4,5]");
        assertThat(json("a\rb\bc\fd")).isEqualTo("\"a\\rb\\bc\\fd\"");
        assertThat(json(1.5f)).isEqualTo("1.5");
    }
}
