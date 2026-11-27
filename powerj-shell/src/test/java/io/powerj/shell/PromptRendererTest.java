package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.format.Ansi;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/**
 * {@code PJ <dir>> }, with a marker for the preview and the perf modes, in color on a terminal that has colors (FR-01);
 * and the prompt computed by {@code $prompt}, with the default prompt and a message when it does not give one (FR-79).
 */
@Timeout(30)
class PromptRendererTest {

    @TempDir
    Path tmp;

    private String render(ShellMode mode, boolean colors) throws Exception {
        try (var screen = new TestTerminal("")) {
            var modes = new ModeController();
            modes.switchTo(mode);
            var session = new Session(tmp, tmp, Map.of());
            return new PromptRenderer(session, modes, screen.terminal(), colors).render();
        }
    }

    @Test
    void theNormalPromptIsPJAndTheCurrentDirectory() throws Exception {
        assertThat(render(ShellMode.NORMAL, false)).isEqualTo("PJ " + tmp.toAbsolutePath().normalize() + "> ");
    }

    @Test
    void thePreviewAndPerfModesHaveTheirMarker() throws Exception {
        assertThat(render(ShellMode.PREVIEW, false)).startsWith("PJ? ").endsWith("> ");
        assertThat(render(ShellMode.PERF, false)).startsWith("PJ% ").endsWith("> ");
    }

    @Test
    void theMarkerIsInColorOnATerminalThatHasColorsAndTheNormalPromptIsNot() throws Exception {
        assertThat(render(ShellMode.PREVIEW, true)).contains("\033[").contains("PJ?");
        assertThat(render(ShellMode.PERF, true)).contains("\033[").contains("PJ%");
        assertThat(render(ShellMode.NORMAL, true)).doesNotContain("\033[");
    }

    @Test
    void theTextForJLineDoublesEveryPercentSignAndNothingElse() throws Exception {
        try (var screen = new TestTerminal("")) {
            var session = new Session(tmp, tmp.resolve("100%N"), Map.of());
            var modes = new ModeController();
            modes.switchTo(ShellMode.PERF);
            var prompt = new PromptRenderer(session, modes, screen.terminal(), false);

            assertThat(prompt.render()).startsWith("PJ% ").endsWith("100%N> ");
            assertThat(prompt.forJLine()).startsWith("PJ%% ").endsWith("100%%N> ");
        }
    }

    // --- $prompt (FR-79) ---

    private final Map<String, String> environment = new HashMap<>();
    private final ModeController modes = new ModeController();
    private final List<String> problems = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;
    private TestTerminal screen;

    /** A prompt that evaluates {@code $prompt}, after the lines typed before it. */
    private PromptRenderer prompt(String... lines) throws Exception {
        session = new Session(tmp, tmp, environment);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false,
                () -> 120), Map.of(), CmdletRegistry.discover());
        for (String line : lines) {
            interpreter.execute(line);
        }
        screen = new TestTerminal("");
        return new PromptRenderer(session, modes, screen.terminal(), false, interpreter::callDetached, message -> {
            if (message != null) {
                problems.add(message);
            }
        });
    }

    @AfterEach
    void closeTheTerminal() throws Exception {
        if (screen != null) {
            screen.close();
        }
    }

    private String defaultPrompt() {
        return "PJ " + tmp.toAbsolutePath().normalize() + "> ";
    }

    @Test
    void withoutPromptVariableTheDefaultPromptIsShownAndNothingIsSaid() throws Exception {
        assertThat(prompt().render()).isEqualTo(defaultPrompt());
        assertThat(problems).isEmpty();
    }

    @Test
    void theTextOfTheBlockIsThePrompt() throws Exception {
        var prompt = prompt("$prompt = { \"λ \" + $pwd.fileName + \" $ \" }");

        assertThat(prompt.render()).isEqualTo("λ " + tmp.getFileName() + " $ ");
        assertThat(problems).isEmpty();
    }

    @Test
    void theBlockIsEvaluatedAgainForEachPromptAndSeesTheCurrentDirectory() throws Exception {
        var prompt = prompt("$prompt = { $pwd.fileName + \"> \" }", "mkdir sub");

        assertThat(prompt.render()).isEqualTo(tmp.getFileName() + "> ");
        interpreter.execute("cd sub");
        assertThat(prompt.render()).isEqualTo("sub> ");
    }

    @Test
    void promptSetToNullGivesTheDefaultPromptBack() throws Exception {
        var prompt = prompt("$prompt = { \"custom> \" }", "$prompt = null");

        assertThat(prompt.render()).isEqualTo(defaultPrompt());
        assertThat(problems).isEmpty();
    }

    @Test
    void theResultOfTheBlockIsShownAsItIsAndNeverReadAsAJLinePattern() throws Exception {
        var prompt = prompt("$prompt = { \"100%N-%M-%%x %{bold%} > \" }");

        assertThat(prompt.render()).isEqualTo("100%N-%M-%%x %{bold%} > ");
        assertThat(prompt.forJLine()).isEqualTo("100%%N-%%M-%%%%x %%{bold%%} > ");
    }

    @Test
    void aBlockThatFailsGivesTheDefaultPromptAndSaysWhy() throws Exception {
        var prompt = prompt("$prompt = { \"abc\".charAt(10) }");

        assertThat(prompt.render()).isEqualTo(defaultPrompt());
        assertThat(problems).singleElement().asString()
                .startsWith("$prompt failed: ")
                .contains("StringIndexOutOfBoundsException")
                .endsWith("(the default prompt is shown)");
    }

    @Test
    void theErrorComesBackAtEachPromptUntilTheBlockIsFixed() throws Exception {
        var prompt = prompt("$prompt = { \"abc\".charAt(10) }");

        prompt.render();
        prompt.render();
        assertThat(problems).hasSize(2);

        interpreter.execute("$prompt = { \"fixed> \" }");
        assertThat(prompt.render()).isEqualTo("fixed> ");
        assertThat(problems).hasSize(2);
    }

    @Test
    void withDebugTheMessageHasTheJavaStackTrace() throws Exception {
        var prompt = prompt("$prompt = { \"abc\".charAt(10) }", "$debug = true");

        prompt.render();

        assertThat(problems).singleElement().asString().contains("\tat java.base/");
    }

    @Test
    void aBlockThatReturnsNullGivesTheDefaultPrompt() throws Exception {
        var prompt = prompt("$prompt = { null }");

        assertThat(prompt.render()).isEqualTo(defaultPrompt());
        assertThat(problems).containsExactly("$prompt returned null (the default prompt is shown)");
    }

    @Test
    void aValueThatIsNotABlockGivesTheDefaultPromptAndSaysHowToWriteIt() throws Exception {
        var prompt = prompt("$prompt = \"PJ> \"");

        assertThat(prompt.render()).isEqualTo(defaultPrompt());
        assertThat(problems).singleElement().asString()
                .contains("$prompt must be a block such as { \"PJ \" + $pwd + \"> \" }, not a String");
    }

    @Test
    void aBlockSlowerThanTheLimitGivesTheDefaultPrompt() throws Exception {
        environment.put(PromptRenderer.TIMEOUT_VARIABLE, "50");
        var prompt = prompt("$prompt = { Thread.sleep(20000) }");

        assertThat(prompt.render()).isEqualTo(defaultPrompt());
        assertThat(problems).containsExactly(
                "$prompt took longer than 50 ms, the limit set by POWERJ_PROMPT_TIMEOUT_MS (the default prompt is shown)");
    }

    @Test
    void aBlockThatOutlivesItsLimitIsNotStartedAgainWhileItRuns() throws Exception {
        environment.put(PromptRenderer.TIMEOUT_VARIABLE, "50");
        var prompt = prompt("$gate = new Semaphore(0)", "$prompt = { $gate.acquireUninterruptibly() }");
        try {
            prompt.render(); // times out; the block does not stop when it is interrupted
            assertThat(prompt.render()).isEqualTo(defaultPrompt());

            assertThat(problems).hasSize(2);
            assertThat(problems.get(1))
                    .isEqualTo("$prompt has not ended since the previous line (the default prompt is shown)");
        } finally {
            ((java.util.concurrent.Semaphore) session.variable("gate")).release(10);
        }
    }

    @Test
    void ctrlCDuringTheEvaluationGivesTheDefaultPromptAtOnce() throws Exception {
        environment.put(PromptRenderer.TIMEOUT_VARIABLE, "10000");
        var prompt = prompt("$prompt = { Thread.sleep(20000) }");

        var shown = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> shown.complete(prompt.render()));
        while (!shown.isDone()) {
            prompt.cancel(); // nothing to cancel until the evaluation has started
            Thread.sleep(5);
        }

        assertThat(shown.get(10, TimeUnit.SECONDS)).isEqualTo(defaultPrompt());
        assertThat(problems).containsExactly("$prompt interrupted (Ctrl+C) (the default prompt is shown)");
    }

    @Test
    void theModeIsInPowerjModeCurrentWhenTheBlockIsEvaluated() throws Exception {
        var prompt = prompt("$prompt = { $POWERJ_MODE_CURRENT + \"> \" }");

        assertThat(prompt.render()).isEqualTo("normal> ");
        modes.switchTo(ShellMode.PREVIEW);
        assertThat(prompt.render()).isEqualTo("preview> ");
        modes.switchTo(ShellMode.PERF);
        assertThat(prompt.render()).isEqualTo("perf> ");
    }

    @Test
    void theModeVariableExistsFromTheStartEvenWithoutPrompt() throws Exception {
        prompt();

        assertThat(session.variable(PromptRenderer.MODE_VARIABLE)).isEqualTo("normal");
        assertThat(session.environment()).doesNotContainKey(PromptRenderer.MODE_VARIABLE);
    }

    @Test
    void evaluatingThePromptLeavesTheStateOfTheLastLineAlone() throws Exception {
        var prompt = prompt("$prompt = { \"abc\".charAt(10) }", "\"abc\".charAt(10)");
        int errors = session.recentErrors().size();

        prompt.render();

        assertThat(session.lastSucceeded()).isFalse();
        assertThat(session.recentErrors()).hasSize(errors);
    }

    @Test
    void theBlockCanShowTheStatusOfTheLastLine() throws Exception {
        var prompt = prompt("$prompt = { ($? ? \"\" : \"x \") + \"> \" }", "\"abc\".charAt(10)");

        assertThat(prompt.render()).isEqualTo("x > ");
        interpreter.execute("1 + 1");
        assertThat(prompt.render()).isEqualTo("> ");
    }

    @Test
    void theColorsOfAnsiReachTheTerminalAndCountForNothingInTheEscaping() throws Exception {
        var prompt = prompt();
        session.setVariable(InteractiveShell.ANSI_VARIABLE, new Ansi(true));
        interpreter.execute("$prompt = { $ansi.green(\"main\") + $ansi.bold(\" 5%\") + \"> \" }");

        assertThat(prompt.render()).isEqualTo("\033[32mmain\033[39m\033[1m 5%\033[22m> ");
    }

    @Test
    void withoutColorsAnsiLeavesTheTextAlone() throws Exception {
        var prompt = prompt();
        session.setVariable(InteractiveShell.ANSI_VARIABLE, new Ansi(false));
        interpreter.execute("$prompt = { $ansi.green($pwd.fileName) + \"> \" }");

        assertThat(prompt.render()).isEqualTo(tmp.getFileName() + "> ");
    }

    @Test
    void aTimeoutThatIsNotAPositiveNumberIsTheDefaultOne() throws Exception {
        environment.put(PromptRenderer.TIMEOUT_VARIABLE, "soon");
        var prompt = prompt("$prompt = { Thread.sleep(20000) }");

        prompt.render();

        assertThat(problems).singleElement().asString()
                .contains("longer than " + PromptRenderer.DEFAULT_TIMEOUT_MILLIS + " ms");
    }
}
