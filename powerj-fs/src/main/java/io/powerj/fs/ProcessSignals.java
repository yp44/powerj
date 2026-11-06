package io.powerj.fs;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The one signal that Java cannot send to a process: {@code SIGCONT}, through the FFM API ({@code kill(2)}), without starting
 * a program. {@code ProcessHandle.destroy()} sends {@code SIGTERM} and {@code destroyForcibly()} {@code SIGKILL}, nothing else.
 * <p>
 * A process stopped by the terminal (a background job that reads it, or writes to it after {@code stty tostop}) keeps
 * {@code SIGTERM} pending until it is continued, so stopping it takes a {@code SIGTERM} <b>then</b> a {@code SIGCONT}.
 * Nothing happens on Windows, which has no such signal.
 * <p>
 * The restricted-method warning of the FFM call is not counted in {@code docs/quality/javac-warnings.txt}: the baseline may
 * not go up, and the call is the point of the class.
 */
@SuppressWarnings("restricted")
public final class ProcessSignals {

    /** {@code SIGCONT} is 18 on Linux and 19 on macOS and the BSDs. */
    private static final int SIGCONT = Host.linux() ? 18 : 19;

    private ProcessSignals() {
    }

    /**
     * Sends {@code SIGCONT} to a process.
     *
     * @return {@code true} if the signal was sent; {@code false} on Windows, if there is no such process or no right to signal it,
     *         or if the system function cannot be called (the process is then left as it is)
     */
    public static boolean resume(long pid) {
        if (Host.windows()) {
            return false;
        }
        try {
            return (int) Native.KILL.invokeExact((int) pid, SIGCONT) == 0;
        } catch (Throwable _) {
            return false;
        }
    }

    /** Binds {@code kill} the first time it is needed: if that fails, the class cannot be initialised and {@link #resume} says no. */
    private static final class Native {
        static final MethodHandle KILL = Linker.nativeLinker().downcallHandle(
                Linker.nativeLinker().defaultLookup().find("kill").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        private Native() {
        }
    }
}
