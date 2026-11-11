package io.powerj.core.nativeexec;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Reads the descriptors of a child that has just been started. Right after {@code exec}, the dynamic loader opens the C
 * library on descriptor 3 and closes it a few hundred microseconds later: a list read in that instant is {@code 0 1 2 3}
 * although nothing leaked. A leak stays, the loader's descriptor goes, so the list is read again until it is the one expected,
 * and only the list that is still wrong when the time is up is a failure.
 */
final class ChildFds {

    private static final Duration LONGEST_WAIT = Duration.ofSeconds(5);
    private static final long PAUSE_MILLIS = 5;

    private ChildFds() {
    }

    /** As {@link #until(Callable, Predicate, Duration)}, for at most five seconds. */
    static List<String> until(Callable<List<String>> lister, Predicate<List<String>> expected) throws Exception {
        return until(lister, expected, LONGEST_WAIT);
    }

    /**
     * Reads with {@code lister} until {@code expected} accepts the list or {@code longest} has passed, and gives back the
     * last list read (the one to show in the message of a failed assertion).
     */
    static List<String> until(Callable<List<String>> lister, Predicate<List<String>> expected, Duration longest)
            throws Exception {
        long deadline = System.nanoTime() + longest.toNanos();
        List<String> read = lister.call();
        while (!expected.test(read) && System.nanoTime() < deadline) {
            Thread.sleep(PAUSE_MILLIS);
            read = lister.call();
        }
        return read;
    }
}
