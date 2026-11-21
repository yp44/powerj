package io.powerj.fx.model;

import java.time.Instant;
import java.util.Date;

/**
 * The instant of a {@link Date}, whatever its subclass. {@code java.sql.Date} and {@code java.sql.Time} extend
 * {@code java.util.Date} but throw {@link UnsupportedOperationException} from {@link Date#toInstant()}: a JDBC result
 * shown in {@code grid} would break the filter, the sort and the copy of its column (audit v2, R-21). Their milliseconds
 * since the epoch are always there; {@code java.sql.Timestamp} keeps its own {@code toInstant}, which has the nanoseconds.
 */
final class Dates {

    private Dates() {
    }

    static Instant instant(Date date) {
        try {
            return date.toInstant();
        } catch (UnsupportedOperationException _) {
            return Instant.ofEpochMilli(date.getTime());
        }
    }
}
