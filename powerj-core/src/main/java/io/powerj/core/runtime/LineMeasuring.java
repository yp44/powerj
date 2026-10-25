package io.powerj.core.runtime;

import io.powerj.core.lang.Ast;

/**
 * What the interpreter needs of the perf mode (FR-76): to know whether it is on, and to start and finish the
 * measure of a line. The mode itself is plugged in from outside, so that the runtime does not know how a line is
 * measured; {@link #OFF} measures nothing.
 */
public interface LineMeasuring {

    /** No perf mode: nothing is measured, and it cannot be switched on. */
    LineMeasuring OFF = new LineMeasuring() {
        @Override
        public boolean on() {
            return false;
        }

        @Override
        public void on(boolean on) {
            // there is no perf mode to switch
        }

        @Override
        public Measurement start(String line, Ast.Script script, long beginNanos, long parseNanos) {
            return Measurement.NONE;
        }
    };

    /** {@code true} while the lines executed are measured. */
    boolean on();

    void on(boolean on);

    /**
     * Starts to measure a line if the mode is on and the line is measurable.
     *
     * @param beginNanos {@code System.nanoTime()} when the line started to be parsed
     * @param parseNanos duration of the parsing
     */
    Measurement start(String line, Ast.Script script, long beginNanos, long parseNanos);

    /** The measure of one line: who is told what happens while it runs, and what to do when it is over. */
    interface Measurement {

        /** A line that is not measured. */
        Measurement NONE = new Measurement() {
            @Override
            public ExecutionObserver observer() {
                return ExecutionObserver.NONE;
            }

            @Override
            public void finish(boolean cancelled) {
                // not measured
            }
        };

        /** Who receives what the execution reports. */
        ExecutionObserver observer();

        /** The line is over, ended or cancelled: keeps its report and prints its summary. */
        void finish(boolean cancelled);
    }
}
