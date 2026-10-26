package io.powerj.cmdlets;

import io.powerj.api.UsageException;

/** The count option that {@code first} and {@code last} share. */
final class Counts {

    private Counts() {
    }

    /** The number of objects asked for: 1 when the option is absent, 0 is allowed, a negative number is a usage error. */
    static int orOne(Integer count) {
        if (count == null) {
            return 1;
        }
        if (count < 0) {
            throw new UsageException(Messages.get("count.negative", count));
        }
        return count;
    }
}
