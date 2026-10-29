package io.powerj.core.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/** Result of {@link Interpreter#preview} (FR-66). */
public sealed interface PreviewResult {

    /** Nothing to preview (empty line). */
    record Empty() implements PreviewResult { }

    /**
     * Values produced.
     *
     * @param values first values (unrolled), at most the requested limit
     * @param lines  the values in the display format (tables for records), formatted during the preview: a lazy
     *               value (stream in a list…) is computed under the same checks as the line
     * @param more   other values follow, or the evaluation was stopped before its end
     * @param errors number of non-blocking errors (objects skipped)
     * @param partial a stage was cut at the limit of the preview ({@link PreviewSettings#stageLimit()}): the values
     *               come from the beginning of its input only, a count or a sort is not the real one
     */
    record Values(List<Object> values, List<String> lines, boolean more, int errors, boolean partial)
            implements PreviewResult {
        public Values {
            values = Collections.unmodifiableList(new ArrayList<>(values));
            lines = List.copyOf(lines);
        }

        /** Values from the whole of the input of every stage. */
        public Values(List<Object> values, List<String> lines, boolean more, int errors) {
            this(values, lines, more, errors, false);
        }
    }

    /**
     * The time limit of the caller stopped the evaluation before it had produced any value. That says nothing of the
     * line: a stage that only emits when its input has ended ({@code sort}, {@code group}) has nothing to show until
     * the stage before it is done. Not the same as {@link Values} with no value, where the line has no result.
     *
     * @param errors number of non-blocking errors (objects skipped) until then
     */
    record TimedOut(int errors) implements PreviewResult { }

    /** The line contains something that is never run in preview mode; {@code reason} says what. */
    record Off(String reason) implements PreviewResult { }

    /** The line is not (yet) valid: syntax error while typing. */
    record Incomplete(String message) implements PreviewResult { }

    /** The evaluation failed (blocking error). */
    record Failed(String message) implements PreviewResult { }
}
