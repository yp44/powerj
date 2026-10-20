package io.powerj.core.exec;

/**
 * Flight Recorder events (FR-76b): {@code powerj.Command} for each line executed and {@code powerj.Stage} for each
 * stage of a pipeline, so that a recording ({@code -XX:StartFlightRecording}) can be read next to the JVM's own
 * events (CPU samples, allocations, GC) and the {@code powerj-*} threads. The events carry command names only, never
 * arguments, because a recording is often shared.
 *
 * <p>{@code jdk.jfr} is an optional module: an image without it records nothing, and costs one boolean test.
 */
final class Profiling {

    /** A period being measured. */
    interface Span {
        /** Ends the period and records it if a recording asked for it. */
        void end(long in, long out);
    }

    private static final Span NONE = (_, _) -> { };

    /** {@code true} if the Flight Recorder is part of the runtime. */
    private static final boolean AVAILABLE = ModuleLayer.boot().findModule("jdk.jfr").isPresent();

    private Profiling() {
    }

    /** {@code true} if a recording is asking for the events, so that the instrumentation is worth its cost. */
    static boolean enabled() {
        return AVAILABLE && JfrSpans.enabled();
    }

    /** A line being executed; {@code commands} are the names of its commands. */
    static Span command(String commands, int statements) {
        return AVAILABLE ? JfrSpans.command(commands, statements) : NONE;
    }

    /** A stage being run; {@code in} / {@code out} given at the end are {@code -1} if unknown. */
    static Span stage(String command, String kind) {
        return AVAILABLE ? JfrSpans.stage(command, kind) : NONE;
    }
}
