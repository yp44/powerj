package io.powerj.cmdlets;

import java.util.concurrent.CancellationException;

/**
 * Text that lets Ctrl+C stop a regular expression (review C-18). {@code java.util.regex} never looks at the interrupt
 * flag, so a pattern that backtracks for ever, like {@code (.*a){30}} on a line of {@code a}s, kept the command running
 * until the second Ctrl+C abandoned it. The matcher reads its input through {@link #charAt}, which checks the flag every few
 * thousand reads and throws {@link CancellationException}.
 */
final class InterruptibleText implements CharSequence {

    /** A power of two minus one: a mask on the number of reads. */
    private static final int CHECK_MASK = (1 << 10) - 1;

    private final CharSequence text;
    private int reads;

    InterruptibleText(CharSequence text) {
        this.text = text;
    }

    @Override
    public char charAt(int index) {
        if ((++reads & CHECK_MASK) == 0 && Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
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
