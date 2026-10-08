package io.powerj.core.exec;

import java.util.List;
import java.util.Objects;

/**
 * Result of executing a line (specification FR-56). The REPL never sees an exception:
 * it handles these four cases exhaustively.
 */
public sealed interface Outcome {

    /** The command completed normally and produced these values. */
    record Success(List<Object> values) implements Outcome {
        public Success {
            values = List.copyOf(values);
        }
    }

    /** The command failed. */
    record Failure(PjError error) implements Outcome {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }

    /** The command was cancelled by Ctrl+C. */
    record Cancelled() implements Outcome { }

    /** The command no longer responded to cancellation and was abandoned (FR-57, level 3). */
    record Abandoned(String commandLine) implements Outcome {
        public Abandoned {
            Objects.requireNonNull(commandLine, "commandLine");
        }
    }
}
