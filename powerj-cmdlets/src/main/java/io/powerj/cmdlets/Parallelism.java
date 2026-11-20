package io.powerj.cmdlets;

import io.powerj.api.UsageException;

/**
 * The value of {@code -p/--parallel}, shared by {@code ls} and {@code grep}, which read, and {@code cp} and {@code rm}, which
 * change (specification FR-35, FR-64, FR-72b): the number of files or folders handled at the same time.
 */
final class Parallelism {

    private Parallelism() {
    }

    /** Checks the value of {@code -p/--parallel}: {@code null} is sequential, below 1 is a usage error. */
    static int of(Integer parallel) {
        if (parallel != null && parallel < 1) {
            throw new UsageException(Messages.get("parallel.invalid", parallel));
        }
        return parallel == null ? 1 : parallel;
    }
}
