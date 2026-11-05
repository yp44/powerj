package io.powerj.fx.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import io.powerj.text.GuardedText;

/**
 * The filter {@code /regex/}: the cell as shown contains a match. A regular expression can take a time that grows with the
 * square, the cube or the exponential of the text ({@code (a+)+$} on a run of a's), and the filter runs on the toolkit
 * thread: it would freeze the window and nothing, not even Ctrl+C, could stop it (review C-18). So the work on each cell
 * has a budget, counted in characters read; a cell that exhausts it counts as not matching, and after
 * {@value #GIVE_UP_AFTER} such cells the others are not tried at all. The number of cells given up on is
 * {@link #abandoned()}: the status bar tells the user that the result is not complete.
 */
final class RegexFilter implements ColumnFilter {

    /** Characters a match may read, whatever the cell. */
    static final int BASE_STEPS = 500_000;
    /** And this many more for each character of the cell: a regex that reads the text a few times is not slow. */
    static final int STEPS_PER_CHAR = 10;
    /** Cells that may exhaust their budget before the regex is not tried on the others. */
    static final int GIVE_UP_AFTER = 10;

    private final Pattern pattern;
    private final ColumnKind display;
    private final AtomicInteger abandoned = new AtomicInteger();

    RegexFilter(Pattern pattern, ColumnKind display) {
        this.pattern = pattern;
        this.display = display;
    }

    @Override
    public boolean test(Object value) {
        if (abandoned.get() >= GIVE_UP_AFTER) {
            abandoned.incrementAndGet();
            return false;
        }
        String text = CellFormatter.fullText(value, display);
        try {
            return pattern.matcher(GuardedText.withBudget(text, BASE_STEPS + (long) STEPS_PER_CHAR * text.length()))
                    .find();
        } catch (GuardedText.Stopped e) {
            abandoned.incrementAndGet();
            return false;
        }
    }

    @Override
    public int abandoned() {
        return abandoned.get();
    }
}
