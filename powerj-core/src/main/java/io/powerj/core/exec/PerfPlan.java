package io.powerj.core.exec;

import java.util.List;

import io.powerj.core.lang.Connector;

/**
 * Result of {@link Interpreter#plan}: how a line will run, worked out without running anything (perf mode, FR-76).
 */
public sealed interface PerfPlan {

    /** Nothing to plan (empty line). */
    record Empty() implements PerfPlan { }

    /** The line is not (yet) valid: syntax error while typing. */
    record Incomplete(String message) implements PerfPlan { }

    /** The statements of the line, in order. */
    record Statements(List<Statement> statements) implements PerfPlan {
        public Statements {
            statements = List.copyOf(statements);
        }
    }

    /**
     * One statement.
     *
     * @param connector how it is chained to the previous one
     * @param assignTo  assigned variable, or {@code null}
     * @param stages    the units that run: consecutive native commands form a single stage
     */
    record Statement(Connector connector, String assignTo, boolean background, int redirects,
                     List<Stage> stages) {
        public Statement {
            stages = List.copyOf(stages);
        }
    }

    /**
     * One unit of a pipeline.
     *
     * @param text      the command as typed (name and arguments), or {@code "{expression}"}
     * @param kind      what it resolves to
     * @param placement the thread it runs in
     */
    record Stage(String text, Kind kind, Placement placement) { }

    /** What a stage resolves to. */
    enum Kind { EXPRESSION, BUILTIN, CMDLET, NATIVE, UNKNOWN }

    /**
     * Where a stage runs: {@code THREAD} in its own virtual thread, {@code CALLER} in the thread that runs the line
     * (last stage of a pipeline, or a lone stage), {@code PROCESS} an external process started directly.
     */
    enum Placement { THREAD, CALLER, PROCESS }
}
