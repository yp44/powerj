package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;

class ValueOrderTest {

    @Test
    void datesOfDifferentTypesAreComparedByTime() {
        assertThat(ValueOrder.INSTANCE.compare(Instant.parse("2026-10-05T10:00:00Z"),
                Date.from(Instant.parse("2026-10-04T10:00:00Z")))).isPositive();
    }

    @Test
    void aSqlDateIsComparedByTimeWithTheOtherDates() {
        // java.sql.Date.toInstant() throws UnsupportedOperationException (audit v2, R-21)
        var sqlDate = new SqlDate(Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(ValueOrder.INSTANCE.compare(sqlDate, Instant.parse("2026-10-06T00:00:00Z"))).isNegative();
        assertThat(ValueOrder.INSTANCE.compare(new Date(0), sqlDate)).isNegative();
        assertThat(ValueOrder.INSTANCE.compare(new SqlDate(Instant.ofEpochSecond(1)), new Date(0))).isPositive();
    }
}
