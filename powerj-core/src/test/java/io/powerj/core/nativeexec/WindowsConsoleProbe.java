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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Issue #128 (Windows), a helper program of {@code WindowsSpawnTest}: the process that it starts <b>in a console of
 * its own</b> (so that the Ctrl+C sent here reaches nothing of the build), with the standard handles of that console as a
 * program started in a terminal has them. It starts several {@code ping -t} and sends
 * {@code GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0)}, which goes to every process of the console:
 * <ul>
 *   <li>A, A2, A3: {@link ProcessBuilder}, with all streams redirected to the null device (A), with the configuration of a
 *       PowerJ background command at a terminal (input from the null device, output and errors inherited: A2), and with
 *       everything inherited (A3);</li>
 *   <li>B, B2, B3: {@link WindowsSpawn} in a new process group, with the null device (B), with the standard handles of the
 *       console (B2), and with output and errors inherited through the handle list (B3: what a job at a terminal gets);</li>
 *   <li>C, C2: {@link WindowsSpawn} without the flag: the controls that show that B is spared by the flag and not by the
 *       way we start it.</li>
 * </ul>
 * It writes {@code key=value} lines to the file given as argument: for each child, its pid, whether the console lists it,
 * whether it ended and with which code.
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

    /** A child, whoever started it. */
    private interface Child {
        long pid();

        boolean alive();

        /** The exit code, or {@code null} while it runs. */
        Integer exit();

        void kill();
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
        lines.add("console_window=" + (WindowsTestSupport.consoleWindow() != 0));
        // If a parent had turned Ctrl+C off for its children, turn it back on for this process and for what it starts.
        lines.add("ctrl_c_enabled=" + WindowsTestSupport.setCtrlHandler(MemorySegment.NULL, false));
        MemorySegment handler = Linker.nativeLinker().upcallStub(
                MethodHandles.lookup().findStatic(WindowsConsoleProbe.class, "onCtrl",
                        MethodType.methodType(int.class, int.class)),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT), Arena.global());
        lines.add("handler_installed=" + WindowsTestSupport.setCtrlHandler(handler, true));

        Path ping = Path.of(System.getenv("SystemRoot"), "System32", "PING.EXE");
        File nul = new File("NUL");
        Map<String, Child> children = new LinkedHashMap<>();
        try {
            children.put("A", of(new ProcessBuilder(ping.toString(), "-t", "127.0.0.1")
                    .redirectInput(ProcessBuilder.Redirect.from(nul)).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()));
            children.put("A2", of(new ProcessBuilder(ping.toString(), "-t", "127.0.0.1")
                    .redirectInput(ProcessBuilder.Redirect.from(nul)).redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start()));
            children.put("A3", of(new ProcessBuilder(ping.toString(), "-t", "127.0.0.1").inheritIO().start()));
            WindowsSpawn.Spec spec = WindowsSpawn.Spec.of(ping, "-t", "127.0.0.1");
            children.put("B", of(WindowsSpawn.spawn(spec.flags(WindowsSpawn.CREATE_NEW_PROCESS_GROUP))));
            children.put("B2", of(WindowsSpawn.spawn(spec.flags(WindowsSpawn.CREATE_NEW_PROCESS_GROUP)
                    .standardHandles(false))));
            // What a job at a terminal does: a new group and the output and errors of the console inherited (the handle list
            // holds the console handles of this process).
            boolean outputInheritableBefore = WindowsTestSupport.standardOutputInheritable();
            children.put("B3", of(WindowsSpawn.spawn(spec.flags(WindowsSpawn.CREATE_NEW_PROCESS_GROUP)
                    .out(WindowsSpawn.Stdio.INHERIT).err(WindowsSpawn.Stdio.INHERIT))));
            lines.add("stdout_inheritable_before=" + outputInheritableBefore);
            lines.add("stdout_inheritable_after=" + WindowsTestSupport.standardOutputInheritable());
            children.put("C", of(WindowsSpawn.spawn(spec)));
            children.put("C2", of(WindowsSpawn.spawn(spec.standardHandles(false))));

            Thread.sleep(2000); // let them all start
            int[] attached = WindowsTestSupport.consoleProcessIds();
            lines.add("console_processes=" + Arrays.toString(attached));
            for (Map.Entry<String, Child> entry : children.entrySet()) {
                Child child = entry.getValue();
                lines.add(entry.getKey() + "_pid=" + child.pid());
                lines.add(entry.getKey() + "_alive_before=" + child.alive());
                lines.add(entry.getKey() + "_attached=" + Arrays.stream(attached).anyMatch(id -> id == child.pid()));
            }

            lines.add("ctrl_c_sent=" + WindowsTestSupport.ctrlEvent(WindowsTestSupport.CTRL_C_EVENT, 0));

            // A death by Ctrl+C is immediate: this is the one wait of the probe that has to be a duration, because
            // "it did not die" can only be seen by letting time pass.
            Thread.sleep(3000);
            for (Map.Entry<String, Child> entry : children.entrySet()) {
                Integer exit = entry.getValue().exit();
                lines.add(entry.getKey() + "_ended=" + (exit != null));
                lines.add(entry.getKey() + "_exit=" + (exit == null ? "-" : Integer.toHexString(exit)));
            }
            lines.add("handler_calls=" + CALLS.get());
        } finally {
            children.values().forEach(Child::kill);
        }
    }

    private static Child of(Process process) {
        return new Child() {
            @Override
            public long pid() {
                return process.pid();
            }

            @Override
            public boolean alive() {
                return process.isAlive();
            }

            @Override
            public Integer exit() {
                return process.isAlive() ? null : process.exitValue();
            }

            @Override
            public void kill() {
                process.destroyForcibly();
            }
        };
    }

    private static Child of(WindowsSpawn.Spawned spawned) {
        return new Child() {
            @Override
            public long pid() {
                return spawned.pid();
            }

            @Override
            public boolean alive() {
                return !spawned.exit().isDone();
            }

            @Override
            public Integer exit() {
                try {
                    return spawned.exit().get(0, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    return null;
                } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void kill() {
                spawned.terminate();
            }
        };
    }
}
