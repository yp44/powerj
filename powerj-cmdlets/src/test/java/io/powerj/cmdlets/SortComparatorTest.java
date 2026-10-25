package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The order of {@code sort} is a total order (review C-16). */
class SortComparatorTest {

    private record Opaque(int n) { }

    private final List<String> errors = new ArrayList<>();

    /** Values of every kind, with several members that compare equal or almost. */
    private static List<Object> samples() {
        List<Object> values = new ArrayList<>(List.of(
                3, 20, 3L, 20L, 2.5, 20.0, -0.0, 0.0, Double.NaN, Float.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, new BigDecimal("1e400"), new BigDecimal("-1e400"), new BigInteger("99999999999999999999"),
                "25", "a", "A", "b", "", "3",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(5), LocalDate.of(2026, 1, 1), LocalDate.of(2025, 1, 1),
                DayOfWeek.MONDAY, DayOfWeek.FRIDAY, true, false, 'c', Path.of("a"), Path.of("b"),
                new Opaque(1), new Opaque(2), List.of(1), List.of(2)));
        values.add(null);
        return values;
    }

    @Test
    void theOrderIsAntisymmetricAndTransitiveOnEveryTripleOfEveryKind() {
        for (boolean ignoreCase : new boolean[] {false, true}) {
            var order = new SortComparator(ignoreCase, errors::add);
            List<Object> values = samples();
            for (Object a : values) {
                for (Object b : values) {
                    int ab = Integer.signum(order.compare(a, b));
                    assertThat(Integer.signum(order.compare(b, a))).as("%s vs %s", a, b).isEqualTo(-ab);
                    for (Object c : values) {
                        if (ab <= 0 && Integer.signum(order.compare(b, c)) <= 0) {
                            assertThat(order.compare(a, c)).as("%s <= %s <= %s", a, b, c).isLessThanOrEqualTo(0);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theCycleOfTheReviewIsGone() {
        var order = new SortComparator(false, errors::add);

        // 3 < 20 (numbers), 20 < "25" (a number before a string), and "25" is no longer before 3.
        assertThat(order.compare(3, 20)).isNegative();
        assertThat(order.compare(20, "25")).isNegative();
        assertThat(order.compare("25", 3)).isPositive();
    }

    @Test
    void kindsAreOrderedNullNumbersStringsComparablesOthers() {
        var order = new SortComparator(false, errors::add);
        List<Object> values = new ArrayList<>(List.of(new Opaque(1), Instant.EPOCH, "text", 7, 1.5));
        values.add(null);
        Collections.shuffle(values, new Random(1));

        values.sort(order);

        assertThat(values).containsExactly(null, 1.5, 7, "text", Instant.EPOCH, new Opaque(1));
    }

    @Test
    void numbersAreOrderedByValueWithTheSpecialValuesAtTheEnds() {
        var order = new SortComparator(false, errors::add);
        List<Object> values = new ArrayList<>(List.of(Double.NaN, 3, Double.POSITIVE_INFINITY, new BigDecimal("1e400"),
                -2L, Double.NEGATIVE_INFINITY, 2.5f, new BigDecimal("-1e400")));
        Collections.shuffle(values, new Random(2));

        values.sort(order);

        assertThat(values).containsExactly(Double.NEGATIVE_INFINITY, new BigDecimal("-1e400"), -2L, 2.5f, 3,
                new BigDecimal("1e400"), Double.POSITIVE_INFINITY, Double.NaN);
    }

    @Test
    void comparablesAreGroupedByClassAndOrderedWithinTheirGroup() {
        var order = new SortComparator(false, errors::add);
        List<Object> values = new ArrayList<>(List.of(LocalDate.of(2026, 1, 1), Instant.EPOCH.plusSeconds(1),
                LocalDate.of(2025, 1, 1), Instant.EPOCH, DayOfWeek.FRIDAY, DayOfWeek.MONDAY));
        Collections.shuffle(values, new Random(3));

        values.sort(order);

        // java.time.DayOfWeek < java.time.Instant < java.time.LocalDate (class names), each by compareTo
        assertThat(values).containsExactly(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
    }

    @Test
    void whatIsNotComparableIsOrderedByItsTextAndReportedOnce() {
        var order = new SortComparator(false, errors::add);

        assertThat(order.compare(new Opaque(1), new Opaque(2))).isNegative();
        assertThat(order.compare(new Opaque(2), new Opaque(1))).isPositive();
        assertThat(errors).containsExactly("cannot compare Opaque with Opaque: these values are sorted by their text");
    }

    @Test
    void mixedKindsAreReportedOnceAndNullsNeverAre() {
        var order = new SortComparator(false, errors::add);

        order.compare(null, 1);
        order.compare("a", null);
        assertThat(errors).isEmpty();

        order.compare(1, "a");
        order.compare("a", Instant.EPOCH);
        assertThat(errors).singleElement().asString().contains("cannot compare Integer with String");
    }

    @Test
    void sortingMixedValuesNeverFailsWithAContractViolation() {
        var order = new SortComparator(false, errors::add);
        Random random = new Random(7);
        List<Object> values = new ArrayList<>();
        List<Object> pool = samples();
        for (int i = 0; i < 5_000; i++) {
            values.add(pool.get(random.nextInt(pool.size())));
        }

        values.sort(order); // TimSort throws "Comparison method violates its general contract" on an inconsistent order

        assertThat(values).hasSize(5_000);
    }
}
