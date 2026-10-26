package io.powerj.fx.model;

import io.powerj.fx.Messages;

/**
 * What the bottom of the window says, worked out from the state of the table and of the input: the text of the status bar,
 * the label of the pause button and whether it can be used, and what an empty table says. A decision, so it is here and
 * not in the view (review C-29).
 *
 * @param text          the status bar
 * @param pauseDisabled nothing is left to pause: everything has arrived and is shown
 * @param pauseLabel    {@code Pause}, or {@code Resume} when the table is paused
 * @param placeholder   what an empty table says: still loading, or no result
 */
public record GridStatus(String text, boolean pauseDisabled, String pauseLabel, String placeholder) {

    private static final String SEPARATOR = "  ·  ";

    /**
     * @param shown    rows that pass the filters
     * @param total    rows received
     * @param state    where the input stands
     * @param paused   the user paused the table
     * @param pending  rows that wait in the queue for the table
     * @param selected rows that are ticked ({@code --pass-thru}), {@code -1} when there is no tick column
     * @param skipped  cells that a regular expression gave up on
     * @param problem  why a filter is not applied ({@link FilterEditor#problem()}), {@code null} or empty if none: said in
     *                 words, so that nobody depends on seeing a colour
     */
    public static GridStatus of(int shown, int total, LiveTable.State state, boolean paused, int pending, int selected,
            int skipped, String problem) {
        String rows = shown != total ? Messages.get("grid.rows.filtered", shown, total)
                : Messages.get(total == 1 ? "grid.rows.one" : "grid.rows", total);
        // Nothing to pause once everything has arrived (and is shown).
        boolean over = state != LiveTable.State.LOADING && pending == 0;
        // Paused with rows still waiting: even if the input is over, the table is not complete.
        String note = paused && !over ? Messages.get("grid.state.paused") : switch (state) {
            case LOADING -> Messages.get("grid.state.loading");
            case COMPLETE -> "";
            case TRUNCATED -> Messages.get("grid.state.truncated", total);
            case INTERRUPTED -> Messages.get("grid.state.interrupted");
        };
        StringBuilder text = new StringBuilder(rows);
        if (problem != null && !problem.isEmpty()) {
            text.append(SEPARATOR).append(problem);
        }
        if (selected >= 0) {
            text.append(SEPARATOR).append(Messages.get("grid.selected", selected));
        }
        if (skipped > 0) {
            text.append(SEPARATOR).append(Messages.get(skipped == 1 ? "grid.filter.skipped.one"
                    : "grid.filter.skipped", skipped));
        }
        if (!note.isEmpty()) {
            text.append(SEPARATOR).append(note);
        }
        return new GridStatus(text.toString(), over, Messages.get(paused ? "grid.resume" : "grid.pause"),
                Messages.get(state == LiveTable.State.LOADING ? "grid.loading" : "grid.empty"));
    }
}
