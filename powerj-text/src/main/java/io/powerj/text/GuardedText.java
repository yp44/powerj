package io.powerj.text;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.LongPredicate;

/**
 * Text that lets a regular expression be stopped (#111). {@code java.util.regex} never looks at the interrupt flag and has
 * no time limit, so a pattern that backtracks for ever, like {@code (.*a){30}} on a line of {@code a}s, keeps its thread
 * (review C-18). The matcher reads its input through {@link #charAt}; this text asks a guard, at each read, whether to go
 * on, and throws {@link Stopped} when it says no.
 * <p>
 * The guard receives the number of reads so far (1 for the first) and answers {@code true} to go on. Two guards are
 * ready made: {@link #interruptible} (Ctrl+C, for a command) and {@link #withBudget} (a number of reads, for a window
 * that must not freeze). One instance guards one match, on one thread: it counts reads and is not thread-safe.
 * <pre>{@code
 * Matcher matcher = pattern.matcher(GuardedText.interruptible(line));
 * boolean found = matcher.find(); // throws GuardedText.Stopped if the thread is interrupted meanwhile
 * }</pre>
 */
public final class GuardedText implements CharSequence {

    /** A power of two minus one: a mask on the number of reads. */
    private static final int CHECK_MASK = (1 << 10) - 1;

    /**
     * Thrown by a read when the guard says to stop. It is a {@link CancellationException}, so the shell treats it as a
     * cancelled command; it carries no stack trace, it is control flow and a matcher may be stopped for every cell of a
     * grid.
     */
    public static final class Stopped extends CancellationException {
        private static final long serialVersionUID = 1L;

        /** A stop, without a message or a stack trace. */
        public Stopped() {
            super();
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private final CharSequence text;
    private final LongPredicate guard;
    private long reads;

    /**
     * Guards {@code text} with {@code guard}, which is asked at each {@link #charAt} with the number of reads so far.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public GuardedText(CharSequence text, LongPredicate guard) {
        this.text = Objects.requireNonNull(text, "text");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /**
     * Text that stops when the current thread is interrupted. The flag is looked at every 1,024 reads, which keeps the
     * cost of a read in the noise (measured on 200,000 lines of 80 characters).
     */
    public static GuardedText interruptible(CharSequence text) {
        return new GuardedText(text, reads -> (reads & CHECK_MASK) != 0 || !Thread.currentThread().isInterrupted());
    }

    /** Text that stops at the read that follows the {@code steps}th: {@code steps} reads are allowed. */
    public static GuardedText withBudget(CharSequence text, long steps) {
        return new GuardedText(text, reads -> reads <= steps);
    }

    @Override
    public char charAt(int index) {
        if (!guard.test(++reads)) {
            throw new Stopped();
        }
        return text.charAt(index);
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return text.subSequence(start, end);
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
