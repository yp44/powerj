package io.powerj.core.runtime;

import java.util.List;
import java.util.function.Consumer;

import io.powerj.core.nativeexec.NativeRun;

/**
 * What the execution tells whoever measures it (perf mode, FR-76): the pipelines, their stages, the pipes between
 * them, and the display of the values. {@link #NONE} measures nothing and costs next to nothing, so that the code that
 * runs a pipeline never asks whether a line is measured: it always reports, and the observer decides what to do.
 */
public interface ExecutionObserver {

    /** Start of the name of a stage thread; the profiler reads the stage number back from the name. */
    String THREAD_PREFIX = "powerj-stage-";

    /** The stage the current thread runs, seen by the blocks it evaluates (so that they are attributed to it). */
    ScopedValue<StageObserver> STAGE = ScopedValue.newInstance();

    /** Name of the thread of a stage that runs apart from the calling thread; the profiler attributes samples by it. */
    static String threadName(int index) {
        return THREAD_PREFIX + index;
    }

    /** Nobody measures. */
    ExecutionObserver NONE = new ExecutionObserver() {
        @Override
        public PipelineObserver pipelineEntered() {
            return PipelineObserver.NONE;
        }

        @Override
        public void displayed(long nanos) {
            // not measured
        }
    };

    /**
     * A pipeline is about to be planned. Only the outermost pipeline of a line is measured, the pipelines of the
     * arguments and sub-expressions count as resolution of that one: the others get {@link PipelineObserver#NONE}.
     */
    PipelineObserver pipelineEntered();

    /** The statement's values have been displayed: the last of it, closing the output, took {@code nanos}. */
    void displayed(long nanos);

    /** Observer of one pipeline, from its planning to its end. */
    interface PipelineObserver {

        /** Not measured. */
        PipelineObserver NONE = new PipelineObserver() {
            @Override
            public void resolved(long nanos) {
                // not measured
            }

            @Override
            public StageObserver stage(int position, String label, boolean nativeStage) {
                return new StageObserver.Unobserved(position);
            }

            @Override
            public Pipe pipe(StageObserver writer, StageObserver reader) {
                return new Pipe();
            }

            @Override
            public void exited() {
                // not measured
            }
        };

        /** The stages have been resolved (arguments evaluated, commands found); it took {@code nanos}. */
        void resolved(long nanos);

        /**
         * One of the stages, in order.
         *
         * @param position  1 for the first stage of the pipeline
         * @param label     what the stage runs, shortened, for the summary
         * @param nativeStage the stage is made of native commands
         */
        StageObserver stage(int position, String label, boolean nativeStage);

        /** The queue that links two stages: a plain one, or one that counts. */
        Pipe pipe(StageObserver writer, StageObserver reader);

        /** The pipeline is over. */
        void exited();
    }

    /** Observer of one stage: the thread it runs in, what it receives and sends, the processes it starts. */
    interface StageObserver {

        /** Body of a stage. */
        @FunctionalInterface
        interface Body {
            boolean run() throws InterruptedException;
        }

        /** Name of the thread of a stage that runs apart from the calling thread. */
        String threadName();

        /** The sink of the last stage, wrapped if its objects are counted; {@code display}: they are displayed. */
        Consumer<Object> sink(Consumer<Object> sink, boolean display);

        /** Runs the stage, timing it. */
        boolean run(Body body) throws InterruptedException;

        /** One evaluation of a block of this stage took {@code nanos}. */
        default void block(String source, long nanos) {
        }

        /** The stage was timed from outside (a single native command): it lasted {@code nanos}. */
        void measured(long nanos);

        /** Native processes the stage started. */
        void natives(List<NativeRun> runs);

        /** This stage writes to the pipe. */
        default void writesTo(MeasuredPipe pipe) {
        }

        /** This stage reads from the pipe. */
        default void readsFrom(MeasuredPipe pipe) {
        }

        /** A stage nobody measures. */
        record Unobserved(int position) implements StageObserver {

            @Override
            public String threadName() {
                return ExecutionObserver.threadName(position);
            }

            @Override
            public Consumer<Object> sink(Consumer<Object> sink, boolean display) {
                return sink;
            }

            @Override
            public boolean run(Body body) throws InterruptedException {
                return body.run();
            }

            @Override
            public void measured(long nanos) {
                // not measured
            }

            @Override
            public void natives(List<NativeRun> runs) {
                // not measured
            }
        }
    }
}
