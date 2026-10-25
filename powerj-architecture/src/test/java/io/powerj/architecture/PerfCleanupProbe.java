package io.powerj.architecture;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;

/**
 * Measures one line in perf mode in a throw-away JVM, tells where the profiler's recording went, and ends normally.
 * Used by {@link PerfCleanupTest}: what is left on disk after the process is gone can only be seen from outside.
 */
public final class PerfCleanupProbe {

    static final String RECORDING = "RECORDING=";

    private PerfCleanupProbe() {
    }

    public static void main(String[] args) throws Exception {
        PrintWriter out = new PrintWriter(System.out, true);
        Session session = new Session(Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath(), Map.of());
        Interpreter interpreter = new Interpreter(session, new ShellIo(out, out::println, false, () -> 120), Map.of(),
                CmdletRegistry.discover());
        interpreter.perfMode(true);
        interpreter.execute("Math.max(1, 2)");
        Path recording = session.perfReports().getFirst().recording();
        out.println(RECORDING + (recording == null ? "none" : recording.toAbsolutePath()));
    }
}
