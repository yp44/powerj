package io.powerj.core.exec;

import java.time.Duration;
import java.util.List;

/**
 * Wall-clock figures of a line that ran in perf mode (FR-76). The stages of a pipeline run at the same time, so
 * their durations overlap and are not meant to be added up: the statement figure is the one that counts.
 *
 * @param total      duration of the whole line, parsing included
 * @param statements the statements that ran (those skipped by {@code &&} / {@code ||} are absent)
 */
public record PerfReport(Duration total, List<Statement> statements) {

    public PerfReport {
        statements = List.copyOf(statements);
    }

    /**
     * @param text     the statement as a pipeline of its stages
     * @param duration time from the start to the end of the whole pipeline
     * @param succeeded outcome of the statement
     */
    public record Statement(String text, Duration duration, boolean succeeded, List<Stage> stages) {
        public Statement {
            stages = List.copyOf(stages);
        }
    }

    /**
     * @param text         the stage as typed
     * @param kind         what it resolved to
     * @param duration     time from the first to the last instant the stage was running
     * @param firstOutput  time until the first value was produced, or {@code null} if it produced none
     * @param in           objects read from the previous stage ({@code -1} for the first stage)
     * @param out          values produced (a collection counts as one)
     */
    public record Stage(String text, PerfPlan.Kind kind, Duration duration, Duration firstOutput, long in, long out) { }
}
