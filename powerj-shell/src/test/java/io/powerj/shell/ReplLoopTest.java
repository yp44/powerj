package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.BuildInfo;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;
import io.powerj.core.runtime.Supervisor;

/**
 * The read loop alone, fed by a script of events instead of a line editor: a line, Ctrl+C, Ctrl+D, a terminal that fails.
 * What the loop does around them: the exit code, the error shown, what runs after each read, the signal handler.
 */
@Timeout(30)
class ReplLoopTest {

    private static final BuildInfo BUILD = new BuildInfo("0.2.0", Runtime.Version.parse("25"));

    @TempDir
    Path tmp;

    private final Deque<Function<String, String>> events = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();
    private int afterInput;

    private void line(String text) {
        events.add(_ -> text);
    }

    private void ctrlC() {
        events.add(_ -> {
            throw new UserInterruptException("");
        });
    }

    private void ctrlD() {
        events.add(_ -> {
            throw new EndOfFileException();
        });
    }

    private void terminalFails() {
        events.add(_ -> {
            throw new IOError(new IOException("the terminal is gone"));
        });
    }

    private Repl repl(TestTerminal screen) {
        Terminal terminal = screen.terminal();
        var out = terminal.writer();
        var errors = new ErrorPrinter(terminal, out);
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        var interpreter = new Interpreter(session, new ShellIo(out, errors::print, false, () -> 120), Map.of(),
                CmdletRegistry.discover());
        var prompt = new PromptRenderer(session, new ModeController(), terminal, false);
        LineEditor editor = text -> {
            prompts.add(text);
            return events.removeFirst().apply(text);
        };
        return new Repl(editor, terminal, new Supervisor(), session, interpreter, prompt, errors, () -> afterInput++);
    }

    private int run() throws Exception {
        try (var screen = new TestTerminal("")) {
            var repl = repl(screen);
            int code = repl.run(BUILD);
            lastScreen = screen.screen();
            return code;
        }
    }

    private String lastScreen;

    @Test
    void theBannerThePromptAndTheExitCodeOfExit() throws Exception {
        line("exit 3");

        assertThat(run()).isEqualTo(3);

        assertThat(lastScreen).contains("PowerJ 0.2.0 (Java 25)");
        assertThat(prompts).containsExactly("PJ " + tmp.toAbsolutePath().normalize() + "> ");
    }

    @Test
    void anErrorIsShownAndTheLoopGoesOn() throws Exception {
        line("nosuchcommand");
        line("exit 2");

        assertThat(run()).isEqualTo(2);

        assertThat(lastScreen).contains("unknown command: nosuchcommand");
        assertThat(prompts).hasSize(2);
    }

    @Test
    void ctrlCDuringInputDropsTheLineAndTheLoopGoesOn() throws Exception {
        ctrlC();
        line("exit 5");

        assertThat(run()).isEqualTo(5);
    }

    @Test
    void ctrlDEndsTheShellWithZero() throws Exception {
        line("1 + 1");
        ctrlD();

        assertThat(run()).isZero();

        assertThat(lastScreen).contains("2");
    }

    @Test
    void aTerminalThatFailsEndsTheShellWithOneInsteadOfThrowing() throws Exception {
        line("1 + 1");
        terminalFails();

        assertThat(run()).isEqualTo(1);
    }

    @Test
    void whatIsShownBelowTheLineIsStoppedAfterEachReadWhateverHappens() throws Exception {
        line("1 + 1");
        ctrlC();
        ctrlD();

        run();

        assertThat(afterInput).isEqualTo(3);
    }

    @Test
    void aHistoryReferenceThatWasNotExpandedIsAnError() throws Exception {
        line("!nothing");
        line("exit");

        run();

        assertThat(lastScreen).contains("history: no command matches !nothing");
    }

    @Test
    void theSignalHandlerThatWasThereIsPutBackWhenTheLoopEnds() throws Exception {
        try (var screen = new TestTerminal("")) {
            Terminal.SignalHandler mine = _ -> { };
            screen.terminal().handle(Terminal.Signal.INT, mine);
            ctrlD();

            repl(screen).run(BUILD);

            assertThat(screen.terminal().handle(Terminal.Signal.INT, Terminal.SignalHandler.SIG_DFL)).isSameAs(mine);
        }
    }
}
