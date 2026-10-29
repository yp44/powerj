package io.powerj.core.runtime;

import java.util.List;
import java.util.Map;

/**
 * What the user can set of the preview mode (FR-66), kept in the environment of the session so that {@code env --set}
 * changes it at once: the time an evaluation may last, and the number of objects a stage may send to the next one.
 * Read at each evaluation; a value that is missing, not a number or not positive is the default one.
 *
 * @param deadlineMillis longest evaluation of the preview, in milliseconds
 * @param stageLimit     objects that each stage but the last sends to the next one in a preview
 */
public record PreviewSettings(int deadlineMillis, int stageLimit) {

    /** Variable of the session environment that holds {@link #deadlineMillis()}. */
    public static final String DEADLINE_VARIABLE = "POWERJ_PREVIEW_DEADLINE_MS";
    /** Variable of the session environment that holds {@link #stageLimit()}. */
    public static final String LIMIT_VARIABLE = "POWERJ_PREVIEW_STAGE_LIMIT";

    public static final int DEFAULT_DEADLINE_MILLIS = 400;
    public static final int DEFAULT_STAGE_LIMIT = 50;

    /** Values that the keys of the preview go through, in increasing order; the last one is the longest deadline. */
    private static final List<Integer> DEADLINE_STEPS = List.of(200, 400, 800, 1600, 3200);
    private static final List<Integer> LIMIT_STEPS = List.of(10, 20, 50, 100, 500);
    /** A deadline beyond this freezes the typing; a larger value is brought back to it. */
    private static final int MAX_DEADLINE_MILLIS = DEADLINE_STEPS.getLast();
    private static final int MAX_STAGE_LIMIT = 100_000;

    public static PreviewSettings defaults() {
        return new PreviewSettings(DEFAULT_DEADLINE_MILLIS, DEFAULT_STAGE_LIMIT);
    }

    /** The settings held by {@code environment}; each variable that is not valid is the default one. */
    public static PreviewSettings from(Map<String, String> environment) {
        return new PreviewSettings(
                Math.min(positive(environment.get(DEADLINE_VARIABLE), DEFAULT_DEADLINE_MILLIS), MAX_DEADLINE_MILLIS),
                Math.min(positive(environment.get(LIMIT_VARIABLE), DEFAULT_STAGE_LIMIT), MAX_STAGE_LIMIT));
    }

    private static int positive(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(text.strip());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    /** Writes the settings into {@code environment}. */
    public void writeTo(Map<String, String> environment) {
        environment.put(DEADLINE_VARIABLE, Integer.toString(deadlineMillis));
        environment.put(LIMIT_VARIABLE, Integer.toString(stageLimit));
    }

    /** The deadline one step longer ({@code direction > 0}) or shorter; unchanged at the end of the steps. */
    public PreviewSettings withDeadlineStep(int direction) {
        return new PreviewSettings(step(DEADLINE_STEPS, deadlineMillis, direction), stageLimit);
    }

    /** The limit one step higher ({@code direction > 0}) or lower; unchanged at the end of the steps. */
    public PreviewSettings withLimitStep(int direction) {
        return new PreviewSettings(deadlineMillis, step(LIMIT_STEPS, stageLimit, direction));
    }

    /** {@code true} if no step is left above the deadline: the keys can no longer lengthen it. */
    public boolean deadlineAtMax() {
        return deadlineMillis >= DEADLINE_STEPS.getLast();
    }

    /** {@code true} if no step is left above the limit. */
    public boolean limitAtMax() {
        return stageLimit >= LIMIT_STEPS.getLast();
    }

    /** The next step above {@code current} (or below, if {@code direction < 0}); {@code current} if there is none. */
    private static int step(List<Integer> steps, int current, int direction) {
        if (direction > 0) {
            return steps.stream().filter(step -> step > current).findFirst().orElse(current);
        }
        return steps.reversed().stream().filter(step -> step < current).findFirst().orElse(current);
    }
}
