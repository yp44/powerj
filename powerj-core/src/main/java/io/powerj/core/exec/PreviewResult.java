package io.powerj.core.exec;

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
     */
    record Values(List<Object> values, List<String> lines, boolean more, int errors) implements PreviewResult {
        public Values {
            values = Collections.unmodifiableList(new ArrayList<>(values));
            lines = List.copyOf(lines);
        }
    }

    /** The line contains something that is never run in preview mode; {@code reason} says what. */
    record Off(String reason) implements PreviewResult { }

    /** The line is not (yet) valid: syntax error while typing. */
    record Incomplete(String message) implements PreviewResult { }

    /** The evaluation failed (blocking error). */
    record Failed(String message) implements PreviewResult { }
}
