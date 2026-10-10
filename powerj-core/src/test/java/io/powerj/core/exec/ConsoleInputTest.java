package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The console input is suspended exactly while a native program reads the console itself (vim…), so that
 * the line editor does not steal its keys.
 */
class ConsoleInputTest {

    @TempDir
    Path tmp;

    private final List<String> events = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    private Interpreter interpreter(boolean interactive) {
        var console = new ConsoleInput() {
            @Override
            public void pause() {
                events.add("pause");
            }

            @Override
            public void resume() {
                events.add("resume");
            }
        };
        var io = new ShellIo(new PrintWriter(new StringWriter(), true), errors::add, interactive, () -> 120, console);
        return new Interpreter(new Session(tmp, tmp, System.getenv()), io, Map.of(), CmdletRegistry.of(List.of()));
    }

    @BeforeEach
    void unixOnly() {
        assumeFalse(Platform.isWindows(), "Unix commands");
    }

    @Test
    void pausedAroundAProgramThatReadsTheConsole() throws Exception {
        interpreter(true).execute("sh -c \"exit 0\"");
        assertThat(events).containsExactly("pause", "resume");
    }

    @Test
    void resumedEvenWhenTheProgramFails() throws Exception {
        interpreter(true).execute("sh -c \"exit 3\"");
        assertThat(events).containsExactly("pause", "resume");
    }

    @Test
    void pausedForTheFirstProgramOfANativePipeline() throws Exception {
        interpreter(true).execute("printf \"a\\n\" | cat");
        assertThat(events).containsExactly("pause", "resume");
    }

    @Test
    void notPausedWhenTheProgramReadsObjects() throws Exception {
        interpreter(true).execute("\"x\" | cat");
        assertThat(events).isEmpty();
    }

    @Test
    void notPausedWithoutAConsole() throws Exception {
        interpreter(false).execute("sh -c \"exit 0\"");
        assertThat(events).isEmpty();
    }
}
