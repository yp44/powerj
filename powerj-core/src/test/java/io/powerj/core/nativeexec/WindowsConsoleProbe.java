package io.powerj.core.nativeexec;

import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PROTOTYPE of issue #128 (Windows), test code: the process that {@code WindowsSpawnPrototypeTest} starts <b>in a console of
 * its own</b> (so that the Ctrl+C sent here reaches nothing of the build) to see what Ctrl+C does to three {@code ping -t}:
 * A, started by {@link ProcessBuilder}; B, started by {@link WindowsSpawn} in a new process group; C, started by
 * {@link WindowsSpawn} without the flag (the control: it shows that B is spared by the flag and not by the way we start it).
 * It sends {@code GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0)}, which goes to every process of the console, and writes
 * {@code key=value} lines to the file given as argument.
 */
@SuppressWarnings("restricted")
public final class WindowsConsoleProbe {

    private static final AtomicInteger CALLS = new AtomicInteger();

    private WindowsConsoleProbe() {
    }

    /** The Ctrl+C handler of this process: it says "handled", so that the JVM does not exit on the event it sends itself. */
    static int onCtrl(int type) {
        CALLS.incrementAndGet();
        return 1;
    }

    public static void main(String[] args) throws IOException {
        Path result = Path.of(args[0]);
        List<String> lines = new ArrayList<>();
        try {
            run(lines);
        } catch (Throwable t) {
            lines.add("error=" + t);
        }
        Files.write(result, lines);
    }

    private static void run(List<String> lines) throws Throwable {
        lines.add("console_window=" + (WindowsSpawn.consoleWindow() != 0));
        // If a parent had turned Ctrl+C off for its children, turn it back on for this process and for A.
        lines.add("ctrl_c_enabled=" + WindowsSpawn.setCtrlHandler(MemorySegment.NULL, false));
        MemorySegment handler = Linker.nativeLinker().upcallStub(
                MethodHandles.lookup().findStatic(WindowsConsoleProbe.class, "onCtrl",
                        MethodType.methodType(int.class, int.class)),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT), Arena.global());
        lines.add("handler_installed=" + WindowsSpawn.setCtrlHandler(handler, true));

        Path ping = Path.of(System.getenv("SystemRoot"), "System32", "PING.EXE");
        Process a = new ProcessBuilder(ping.toString(), "-t", "127.0.0.1")
                .redirectInput(ProcessBuilder.Redirect.from(new File("NUL")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        WindowsSpawn.Spawned b = WindowsSpawn.spawn(WindowsSpawn.Spec.of(ping, "-t", "127.0.0.1")
                .flags(WindowsSpawn.CREATE_NEW_PROCESS_GROUP));
        WindowsSpawn.Spawned c = WindowsSpawn.spawn(WindowsSpawn.Spec.of(ping, "-t", "127.0.0.1"));
        try {
            Thread.sleep(2000); // let the three start
            lines.add("A_alive_before=" + a.isAlive());
            lines.add("B_alive_before=" + !b.exit().isDone());
            lines.add("C_alive_before=" + !c.exit().isDone());

            lines.add("ctrl_c_sent=" + WindowsSpawn.ctrlEvent(WindowsSpawn.CTRL_C_EVENT, 0));

            boolean aEnded = a.waitFor(10, TimeUnit.SECONDS);
            lines.add("A_ended=" + aEnded);
            lines.add("A_exit=" + (aEnded ? Integer.toHexString(a.exitValue()) : "-"));
            Integer cExit = null;
            try {
                cExit = c.exit().get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException _) {
                // C did not end: it is reported below
            }
            lines.add("C_ended=" + (cExit != null));
            lines.add("C_exit=" + (cExit == null ? "-" : Integer.toHexString(cExit)));
            Thread.sleep(1500); // a death of B would be immediate: this is the one wait that has to be a duration
            lines.add("B_alive_after=" + !b.exit().isDone());
            lines.add("handler_calls=" + CALLS.get());
        } finally {
            b.terminate();
            c.terminate();
            a.destroyForcibly();
        }
    }
}
