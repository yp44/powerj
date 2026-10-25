package io.powerj.core.runtime;

import java.util.function.Consumer;

/**
 * Everything a stage needs from its surroundings, besides the objects it receives: the session and the terminal, where
 * its values and its errors go, which redirections apply, and whether a native process may read the shell's standard
 * input. These used to travel as eight or eleven separate parameters, always the same ones in the same order.
 *
 * @param session      the session the stage runs in
 * @param io           the terminal (or its stand-in)
 * @param sink         receives the values of the last stage of the pipeline; {@code null} for the others, whose values go
 *                     to the next stage
 * @param capture      the values are kept (assignment, subexpression): the output of a native command is then read as
 *                     lines instead of being displayed
 * @param redirections {@code >} and {@code 2>} of the statement
 * @param errors       receives the non-blocking errors, from any thread
 * @param inheritInput the first native process of the pipeline reads the shell's standard input directly
 */
public record ExecutionContext(Session session, ShellIo io, Consumer<Object> sink, boolean capture,
                        Redirections redirections, Consumer<String> errors, boolean inheritInput) {

    /** The context of a stage that is not the last: no destination for values, no redirection of the values. */
    ExecutionContext forIntermediateStage() {
        return new ExecutionContext(session, io, null, false, redirections.forIntermediateStages(), errors, inheritInput);
    }
}
