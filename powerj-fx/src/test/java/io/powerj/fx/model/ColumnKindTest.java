package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;

import org.junit.jupiter.api.Test;

import io.powerj.api.Bytes;

class ColumnKindTest {

    private record Sample(int count, @Bytes long size, @Bytes String notANumber, Instant when, String text) {
    }

    @Test
    void numbersAreNumbers() {
        for (Class<?> type : new Class<?>[] {int.class, long.class, double.class, float.class, short.class,
                byte.class, Integer.class, Long.class, BigDecimal.class, java.util.concurrent.atomic.AtomicInteger.class}) {
            assertThat(ColumnKind.ofType(type)).as(type.getName()).isEqualTo(ColumnKind.NUMBER);
        }
    }

    @Test
    void datesAreDates() {
        for (Class<?> type : new Class<?>[] {Instant.class, LocalDate.class, Date.class}) {
            assertThat(ColumnKind.ofType(type)).as(type.getName()).isEqualTo(ColumnKind.DATE);
        }
    }

    @Test
    void everythingElseIsText() {
        for (Class<?> type : new Class<?>[] {String.class, boolean.class, char.class, Boolean.class, Object.class}) {
            assertThat(ColumnKind.ofType(type)).as(type.getName()).isEqualTo(ColumnKind.TEXT);
        }
    }

    @Test
    void bytesMakeASizeOnlyOfANumber() {
        var components = Sample.class.getRecordComponents();
        assertThat(ColumnKind.of(components[0])).isEqualTo(ColumnKind.NUMBER);
        assertThat(ColumnKind.of(components[1])).isEqualTo(ColumnKind.SIZE);
        assertThat(ColumnKind.of(components[2])).isEqualTo(ColumnKind.TEXT);
        assertThat(ColumnKind.of(components[3])).isEqualTo(ColumnKind.DATE);
        assertThat(ColumnKind.of(components[4])).isEqualTo(ColumnKind.TEXT);
    }

    @Test
    void theKindOfAValue() {
        assertThat(ColumnKind.ofValue(null)).isEqualTo(ColumnKind.UNKNOWN);
        assertThat(ColumnKind.ofValue(3)).isEqualTo(ColumnKind.NUMBER);
        assertThat(ColumnKind.ofValue("a")).isEqualTo(ColumnKind.TEXT);
        assertThat(ColumnKind.ofValue(Instant.EPOCH)).isEqualTo(ColumnKind.DATE);
    }

    @Test
    void mergingKeepsTheSameKindAndFallsBackToText() {
        assertThat(ColumnKind.NUMBER.merge(ColumnKind.NUMBER)).isEqualTo(ColumnKind.NUMBER);
        assertThat(ColumnKind.NUMBER.merge(ColumnKind.UNKNOWN)).isEqualTo(ColumnKind.NUMBER);
        assertThat(ColumnKind.UNKNOWN.merge(ColumnKind.DATE)).isEqualTo(ColumnKind.DATE);
        assertThat(ColumnKind.UNKNOWN.merge(ColumnKind.UNKNOWN)).isEqualTo(ColumnKind.UNKNOWN);
        assertThat(ColumnKind.NUMBER.merge(ColumnKind.TEXT)).isEqualTo(ColumnKind.TEXT);
        assertThat(ColumnKind.SIZE.merge(ColumnKind.NUMBER)).isEqualTo(ColumnKind.TEXT);
    }

    @Test
    void numbersAndSizesAreAlignedToTheRight() {
        assertThat(ColumnKind.NUMBER.alignRight()).isTrue();
        assertThat(ColumnKind.SIZE.alignRight()).isTrue();
        assertThat(ColumnKind.TEXT.alignRight()).isFalse();
        assertThat(ColumnKind.DATE.alignRight()).isFalse();
        assertThat(ColumnKind.UNKNOWN.alignRight()).isFalse();
    }

    @Test
    void aColumnIsBuiltAgainOnlyWhenItsKindChangesHowItLooks() {
        // nothing known yet, then text: the same look
        assertThat(ColumnKind.sameLook(null, ColumnKind.UNKNOWN)).isTrue();
        assertThat(ColumnKind.sameLook(null, ColumnKind.TEXT)).isTrue();
        assertThat(ColumnKind.sameLook(ColumnKind.UNKNOWN, ColumnKind.TEXT)).isTrue();
        assertThat(ColumnKind.sameLook(ColumnKind.TEXT, ColumnKind.UNKNOWN)).isTrue();
        // text that turns out to be numbers, sizes or dates looks different
        assertThat(ColumnKind.sameLook(ColumnKind.UNKNOWN, ColumnKind.NUMBER)).isFalse();
        assertThat(ColumnKind.sameLook(ColumnKind.TEXT, ColumnKind.DATE)).isFalse();
        assertThat(ColumnKind.sameLook(null, ColumnKind.SIZE)).isFalse();
        // numbers are not sizes
        assertThat(ColumnKind.sameLook(ColumnKind.NUMBER, ColumnKind.SIZE)).isFalse();
        assertThat(ColumnKind.sameLook(ColumnKind.NUMBER, ColumnKind.TEXT)).isFalse();
        for (ColumnKind kind : ColumnKind.values()) {
            assertThat(ColumnKind.sameLook(kind, kind)).as(kind.name()).isTrue();
        }
    }

    @Test
    void onlyNumbersAndSizesAreFilteredWithAComparisonFromAList() {
        for (ColumnKind kind : ColumnKind.values()) {
            assertThat(kind.hasComparisons()).as(kind.name())
                    .isEqualTo(kind == ColumnKind.NUMBER || kind == ColumnKind.SIZE);
        }
    }
}
