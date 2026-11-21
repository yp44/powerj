package io.powerj.fx.model;

import java.time.Instant;
import java.util.Date;

/**
 * Behaves as {@code java.sql.Date} and {@code java.sql.Time} do for the grid: a {@link Date} whose {@link #toInstant()}
 * throws {@link UnsupportedOperationException}. The module does not read {@code java.sql}, so its tests cannot name the
 * real classes; a JDBC result handed to {@code grid} by a third-party module brings them (audit v2, R-21).
 */
final class SqlDate extends Date {

    private static final long serialVersionUID = 1L;

    SqlDate(Instant instant) {
        super(instant.toEpochMilli());
    }

    @Override
    public Instant toInstant() {
        throw new UnsupportedOperationException();
    }
}
