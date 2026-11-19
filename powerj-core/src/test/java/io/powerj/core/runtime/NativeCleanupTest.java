package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.support.Waits;
import io.powerj.fs.Host;

/**
 * What a native command leaves behind when it goes wrong (audit v2, R-13, S-06; #173): processes that were started
 * before an error was found, and reader threads that outlive their command. Linux and macOS: the commands are Unix's.
 */
class NativeCleanupTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private final Map<String, String> environment = new HashMap<>(System.getenv());
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(Host.windows(), "Unix commands");
        Files.createDirectories(tmp.resolve("home"));
        environment.put("HOME", tmp.resolve("home").toString());
    }

    private void start() {
        var session = new Session(tmp.resolve("home"), tmp, environment);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of()));
    }

    @AfterEach
    void killWhatIsLeft() {
        ProcessHandle.current().descendants().filter(NativeCleanupTest::isSleep).forEach(ProcessHandle::destroyForcibly);
    }

    private static boolean isSleep(ProcessHandle process) {
        return process.info().command().map(command -> command.endsWith("/sleep")).orElse(false);
    }

    @Test
    void anUnusableEncodingIsReportedBeforeAnyProcessOfThePipelineIsStarted() throws Exception {
        environment.put("POWERJ_NATIVE_ENCODING", "bogus");
        start();

        interpreter.execute("sleep 30 | cat");

        assertThat(errors).isNotEmpty();
        Waits.until("no process of the pipeline is left", () -> ProcessHandle.current().descendants()
                .noneMatch(NativeCleanupTest::isSleep));
    }

    /** The JDK closes the pipe of a process that has exited: a process that it left in the background cannot hold a reader. */
    @Test
    void aReaderThreadDoesNotWaitForTheBackgroundProcessesOfACapturedCommand() throws Exception {
        start();

        interpreter.execute("$x = sh -c \"sleep 600 &\"");

        Waits.until("no reader thread is left", () -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().equals("powerj-pump") && thread.isAlive()));
    }
}
