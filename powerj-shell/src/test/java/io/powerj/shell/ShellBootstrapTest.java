package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Language;

/**
 * The start of the shell, without the process: a folder, a few streams and a fake terminal are all it needs. What was
 * before in {@code Main}, which nothing could call without {@code System.exit}.
 */
@Timeout(30)
class ShellBootstrapTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final ByteArrayOutputStream messages = new ByteArrayOutputStream();
    private final Map<String, String> variables = new HashMap<>();
    private final List<String> logged = new ArrayList<>();
    private String stdinText = "";
    private boolean stdinIsTerminal = true;
    private ShellBootstrap.Terminals terminals = _ -> {
        throw new IOException("no terminal in this test");
    };

    @AfterEach
    void restoreLanguage() {
        Language.set(Locale.ENGLISH);
    }

    private Environment environment() {
        variables.putIfAbsent(PowerJHome.HOME_VARIABLE, tmp.resolve("home").toString());
        variables.putIfAbsent("PATH", "");
        return new Environment(Map.copyOf(variables), Map.of(), tmp, tmp.resolve("work"), Locale.ENGLISH,
                new ByteArrayInputStream(stdinText.getBytes(StandardCharsets.UTF_8)), stdout, stderr,
                new PrintStream(messages, true, StandardCharsets.UTF_8), false, stdinIsTerminal);
    }

    private int execute(String... args) throws IOException {
        Files.createDirectories(tmp.resolve("work"));
        return new ShellBootstrap(environment(), terminals, (dir, debug, _) -> logged.add(dir + "|" + debug))
                .execute(args);
    }

    private static String text(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }

    // --- The command line ---

    @Test
    void optionsAreReadWhereverTheyAre() {
        var options = ShellBootstrap.Options.parse("--debug", "-c", "ls", "--no-profile");

        assertThat(options.debug()).isTrue();
        assertThat(options.noProfile()).isTrue();
        assertThat(options.version()).isFalse();
        assertThat(options.script()).containsExactly("-c", "ls");
        assertThat(ShellBootstrap.Options.parse("--version").version()).isTrue();
        assertThat(ShellBootstrap.Options.parse("--help").help()).isTrue();
        assertThat(ShellBootstrap.Options.parse("-h").help()).isTrue();
        assertThat(ShellBootstrap.Options.parse().script()).isEmpty();
    }

    @Test
    void versionPrintsTheBannerAndTouchesNothing() throws Exception {
        assertThat(execute("--version")).isZero();

        assertThat(text(stdout)).startsWith("PowerJ ").contains("(Java ");
        assertThat(tmp.resolve("home")).doesNotExist();
        assertThat(logged).isEmpty();
    }

    @Test
    void helpPrintsTheUsageAndTouchesNothing() throws Exception {
        assertThat(execute("--help")).isZero();

        assertThat(text(stdout)).contains("usage: powerj").contains("--version").contains("--help");
        assertThat(tmp.resolve("home")).doesNotExist();
    }

    @Test
    void aWrongCommandLineGivesTwoAndTheUsageOnTheMessages() throws Exception {
        assertThat(execute("-c")).isEqualTo(2);
        assertThat(execute("a.pj", "b.pj")).isEqualTo(2);
        assertThat(execute("-x")).isEqualTo(2);

        assertThat(text(messages)).contains("usage: powerj");
    }

    // --- The start ---

    @Test
    void theHomeIsCreatedOnceAndTheLogIsInstalledInIt() throws Exception {
        assertThat(execute("-c", "\"x\"")).isZero();

        assertThat(tmp.resolve("home").resolve("logs")).isDirectory();
        assertThat(logged).containsExactly(tmp.resolve("home").resolve("logs") + "|false");
    }

    @Test
    void debugIsPassedToTheLog() throws Exception {
        execute("--debug", "-c", "\"x\"");

        assertThat(logged).containsExactly(tmp.resolve("home").resolve("logs") + "|true");
    }

    @Test
    void anUnusableConfigurationIsSaidAndTheShellStarts() throws Exception {
        Files.createDirectories(tmp.resolve("home"));
        Files.writeString(tmp.resolve("home").resolve("config.properties"), "x=C:\\users\\yves\n");

        assertThat(execute("-c", "\"still here\"")).isZero();

        assertThat(text(messages)).contains("config.properties cannot be used");
        assertThat(text(stdout)).isEqualTo("still here\n");
    }

    @Test
    void theLanguageOfTheConfigurationIsApplied() throws Exception {
        Files.createDirectories(tmp.resolve("home"));
        Files.writeString(tmp.resolve("home").resolve("config.properties"), "language=fr\n");

        assertThat(execute("-c", "inconnue")).isEqualTo(1);

        assertThat(text(stderr)).contains("commande inconnue : inconnue");
    }

    // --- The script mode ---

    @Test
    void aLineOfTheCommandLineRunsInTheWorkingDirectory() throws Exception {
        assertThat(execute("-c", "pwd")).isZero();

        assertThat(text(stdout).strip()).isEqualTo(tmp.resolve("work").toAbsolutePath().toString());
    }

    @Test
    void aScriptFileRunsAndItsExitCodeIsTheExitCode() throws Exception {
        Files.createDirectories(tmp.resolve("work"));
        Files.writeString(tmp.resolve("work").resolve("s.pj"), "# comment\n\"first\"\nexit 5\n\"never\"\n");

        assertThat(execute("s.pj")).isEqualTo(5);

        assertThat(text(stdout)).isEqualTo("first\n");
    }

    @Test
    void aMissingScriptGivesTwoAndSaysWhy() throws Exception {
        assertThat(execute("absent.pj")).isEqualTo(2);

        assertThat(text(messages)).contains("cannot read absent.pj");
    }

    @Test
    void standardInputFeedsTheFirstStageWhenItIsNotATerminal() throws Exception {
        stdinIsTerminal = false;
        stdinText = "b\na\n";

        assertThat(execute("-c", "sort")).isZero();

        assertThat(text(stdout)).isEqualTo("a\nb\n");
    }

    // --- The interactive shell ---

    @Test
    void withoutATerminalTheShellSaysSoAndGivesOne() throws Exception {
        assertThat(execute()).isEqualTo(1);

        assertThat(text(messages)).contains("terminal unavailable (no terminal in this test)");
    }

    @Test
    void theInteractiveShellRunsOnTheTerminalItIsGiven() throws Exception {
        try (var terminal = new TestTerminal("exit 3" + TestTerminal.ENTER)) {
            terminals = _ -> terminal.terminal();
            terminal.startTyping();

            assertThat(execute()).isEqualTo(3);

            assertThat(terminal.screen()).contains("PowerJ ").contains("PJ " + tmp.resolve("work").toAbsolutePath());
        }
    }

    @Test
    void theProfileIsRunUnlessItIsTurnedOff() throws Exception {
        Files.createDirectories(tmp.resolve("home"));
        Files.writeString(tmp.resolve("home").resolve("profile.pj"), "$mark = \"from the profile\"\n");
        try (var terminal = new TestTerminal("$mark" + TestTerminal.ENTER + "exit" + TestTerminal.ENTER)) {
            terminals = _ -> terminal.terminal();
            terminal.startTyping();

            execute();

            assertThat(terminal.screen()).contains("from the profile");
        }
        try (var terminal = new TestTerminal("$mark" + TestTerminal.ENTER + "exit" + TestTerminal.ENTER)) {
            terminals = _ -> terminal.terminal();
            terminal.startTyping();

            execute("--no-profile");

            assertThat(terminal.screen()).contains("unknown variable").doesNotContain("from the profile");
        }
    }
}
