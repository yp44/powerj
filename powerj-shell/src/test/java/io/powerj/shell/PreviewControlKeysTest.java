package io.powerj.shell;

import static io.powerj.shell.TestTerminal.CTRL_C;
import static io.powerj.shell.TestTerminal.ENTER;
import static io.powerj.shell.TestTerminal.UP;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.core.BuildInfo;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.PreviewSettings;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.Supervisor;

/**
 * The control of the preview that timed out (FR-66): Alt+◄► choose a setting, Alt+▲▼ change it, in the banner, without
 * leaving the preview mode; ▲ alone is still the history.
 */
@Timeout(60)
class PreviewControlKeysTest {

    private static final String ALT_V = Previewer.TOGGLE_KEY;
    private static final String ALT_LEFT = "\033[1;3D";
    private static final String ALT_RIGHT = "\033[1;3C";
    private static final String ALT_UP = "\033[1;3A";
    private static final String ALT_DOWN = "\033[1;3B";

    /** The settings are the ones of the first line of the banner, the control shows them. */
    private static final String FULL = "timed out after 200 ms · [duration 200 ms]  max/stage 50";

    public record Thing(String name) { }

    /** A source that sends three objects, then waits for ever. */
    @CmdletInfo(name = "stall", preview = true, category = "Test", summary = "Three objects, then waits",
            examples = "stall")
    public static final class Stall implements Cmdlet<Stall.Params, Void, Thing> {

        public record Params() { }

        @Override
        public void begin(Params params, CmdletContext<Thing> context) throws InterruptedException {
            for (int i = 1; i <= 3; i++) {
                context.emit(new Thing("thing" + i));
            }
            Thread.sleep(Duration.ofMinutes(10));
        }
    }

    /** A blocking cmdlet, like {@code sort}: it emits only when its input has ended. */
    @CmdletInfo(name = "tally", preview = true, category = "Test", summary = "Counts what it receives",
            examples = "stall | tally")
    public static final class Tally implements Cmdlet<Tally.Params, Object, Integer> {

        public record Params() { }

        @Override
        public void process(Params params, Object input, CmdletContext<Integer> context) {
            context.state(AtomicInteger.class, AtomicInteger::new).incrementAndGet();
        }

        @Override
        public void end(Params params, CmdletContext<Integer> context) {
            context.emit(context.state(AtomicInteger.class, AtomicInteger::new).get());
        }
    }

    @TempDir
    Path tmp;

    private TestTerminal terminal;
    private PreviewLineReader reader;
    private CompletableFuture<Integer> code;
    private Session session;

    @BeforeEach
    void start() throws Exception {
        Path cwd = Files.createDirectories(tmp.resolve("dev"));
        var home = new PowerJHome(tmp.resolve("home")).createDirectories();
        terminal = TestTerminal.interactive();
        reader = ShellReader.create(terminal.terminal(), home, ShellConfig.defaults());
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put(PreviewSettings.DEADLINE_VARIABLE, "200");
        session = new Session(tmp, cwd, environment);
        var repl = InteractiveShell.create(reader, new Supervisor(), session,
                CmdletRegistry.of(List.of(new Stall(), new Tally())));
        terminal.startTyping();
        code = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> code.complete(repl.run(new BuildInfo("0.1.0", Runtime.Version.parse("25")))));
        awaitUntil(() -> reader.isReading());
    }

    @AfterEach
    void stop() throws Exception {
        terminal.close();
    }

    private void exit() throws Exception {
        terminal.type(CTRL_C);
        awaitUntil(() -> reader.isReading() && reader.getBuffer().length() == 0);
        terminal.type("exit 0" + ENTER);
        assertThat(code.get(20, TimeUnit.SECONDS)).isZero();
    }

    private void type(String keys) throws Exception {
        terminal.type(keys);
        awaitUntil(() -> reader.isReading() && reader.getBuffer().toString().endsWith(keys));
    }

    /** The banner and the lines below the line being typed, as they are now. JLine redraws only what changes, so
     *  the raw output of the terminal never holds a banner in one piece. */
    private String shown() {
        var preview = reader.preview();
        return preview == null ? "" : preview.toString();
    }

    private String variable(String name) {
        return session.environment().get(name);
    }

    /** The preview mode is on and the line ends with a timeout whose control is on screen. */
    private void timeOut() throws Exception {
        terminal.type(ALT_V);
        type("stall | tally");
        awaitUntil(() -> shown().contains(FULL));
    }

    @Test
    void aTimeoutOffersTheTwoSettingsAndTheKeys() throws Exception {
        timeOut();

        assertThat(shown()).contains("Alt+◄► choose, Alt+▲▼ change").doesNotContain("no result");
        exit();
    }

    @Test
    void altUpLengthensTheDeadlineAndTheLineIsPreviewedAgain() throws Exception {
        timeOut();

        terminal.type(ALT_UP);

        awaitUntil(() -> "400".equals(variable(PreviewSettings.DEADLINE_VARIABLE)));
        awaitUntil(() -> shown().contains("timed out after 400 ms · [duration 400 ms]  max/stage 50"));
        assertThat(variable(PreviewSettings.LIMIT_VARIABLE)).isEqualTo("50");
        exit();
    }

    @Test
    void altRightThenAltDownLowersTheLimitOfTheStages() throws Exception {
        timeOut();

        terminal.type(ALT_RIGHT);
        awaitUntil(() -> shown().contains("duration 200 ms  [max/stage 50]"));
        terminal.type(ALT_DOWN);

        awaitUntil(() -> "20".equals(variable(PreviewSettings.LIMIT_VARIABLE)));
        awaitUntil(() -> shown().contains("duration 200 ms  [max/stage 20]"));
        assertThat(variable(PreviewSettings.DEADLINE_VARIABLE)).isEqualTo("200");
        terminal.type(ALT_LEFT);
        awaitUntil(() -> shown().contains("[duration 200 ms]  max/stage 20"));
        exit();
    }

    @Test
    void theKeysDoNothingMoreAtTheEndOfTheSteps() throws Exception {
        timeOut();

        terminal.type(ALT_DOWN); // 200 ms is the shortest step
        terminal.type(ALT_UP); // then 400: a mark that the keys were read
        awaitUntil(() -> "400".equals(variable(PreviewSettings.DEADLINE_VARIABLE)));

        assertThat(shown()).doesNotContain("[duration 100 ms]");
        exit();
    }

    @Test
    void theKeysActOnlyWhileTheTimeoutIsShown() throws Exception {
        terminal.type(ALT_V);
        type("\"abc\".toUpperCase()");
        awaitUntil(() -> terminal.screen().contains("String · 1"));

        terminal.type(ALT_UP);
        terminal.type(ALT_RIGHT);
        terminal.type(ALT_DOWN);
        type("x"); // the shell has read everything before this key

        assertThat(variable(PreviewSettings.LIMIT_VARIABLE)).isNull();
        assertThat(variable(PreviewSettings.DEADLINE_VARIABLE)).isEqualTo("200"); // as the test set it
        exit();
    }

    @Test
    void theHistoryIsStillOnTheArrowAloneWhileTheControlIsShown() throws Exception {
        // ▲ searches the history for the lines that start like the one being typed; an earlier line is put there
        // without running it (`stall` would wait)
        reader.getHistory().add("stall | tally | tally");
        timeOut();

        terminal.type(UP);

        awaitUntil(() -> reader.getBuffer().toString().equals("stall | tally | tally"));
        assertThat(variable(PreviewSettings.LIMIT_VARIABLE)).isNull();
        assertThat(variable(PreviewSettings.DEADLINE_VARIABLE)).isEqualTo("200"); // as the test set it
        exit();
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition never reached");
            }
            Thread.sleep(10);
        }
    }
}
