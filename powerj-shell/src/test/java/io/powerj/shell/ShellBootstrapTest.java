package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

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

import io.powerj.api.internal.LanguageSetting;

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
        LanguageSetting.set(Locale.ENGLISH);
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
    void theStartDirectoryIsTheNextWordOrWhatFollowsTheEqualSign() {
        var spaced = ShellBootstrap.Options.parse("--cd", "build", "-c", "pwd");
        var equal = ShellBootstrap.Options.parse("--cd=D:\\dev\\my project", "script.pj", "--no-profile");

        assertThat(spaced.startDirectory()).isEqualTo("build");
        assertThat(spaced.usageError()).isFalse();
        assertThat(spaced.script()).containsExactly("-c", "pwd");
        assertThat(equal.startDirectory()).isEqualTo("D:\\dev\\my project");
        assertThat(equal.noProfile()).isTrue();
        assertThat(equal.script()).containsExactly("script.pj");
        assertThat(ShellBootstrap.Options.parse("--cd", "--debug").startDirectory()).isEqualTo("--debug");
        assertThat(ShellBootstrap.Options.parse("--cd", "a", "--cd=b").startDirectory()).isEqualTo("b");
        assertThat(ShellBootstrap.Options.parse("-c", "pwd").startDirectory()).isNull();
    }

    @Test
    void aMissingOrEmptyStartDirectoryIsAUsageError() {
        assertThat(ShellBootstrap.Options.parse("--cd").usageError()).isTrue();
        assertThat(ShellBootstrap.Options.parse("-c", "pwd", "--cd").usageError()).isTrue();
        assertThat(ShellBootstrap.Options.parse("--cd=").usageError()).isTrue();
        assertThat(ShellBootstrap.Options.parse("--cd", "").usageError()).isTrue();
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

        assertThat(text(stdout)).contains("usage: powerj").contains("--cd").contains("--version").contains("--help");
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
    void theBuiltInCmdletsPrepareTheirFilesInTheHome() throws Exception {
        assertThat(execute("-c", "\"x\"")).isZero();

        assertThat(Files.readAllLines(tmp.resolve("home").resolve("protected-paths"))).contains("~");
    }

    @Test
    void anEditedListOfProtectedPathsIsKept() throws Exception {
        Files.createDirectories(tmp.resolve("home"));
        Files.writeString(tmp.resolve("home").resolve("protected-paths"), "# mine\n");

        execute("-c", "\"x\"");

        assertThat(Files.readString(tmp.resolve("home").resolve("protected-paths"))).isEqualTo("# mine\n");
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

    // --- The start-up directory (--cd, FR-04d) ---

    private Path directory(String name) throws IOException {
        return Files.createDirectories(tmp.resolve("work").resolve(name));
    }

    @Test
    void theStartDirectoryMayBeAbsoluteRelativeOrUnderTheHome() throws Exception {
        Path build = directory("build");
        Path docs = Files.createDirectories(tmp.resolve("userhome").resolve("docs"));
        variables.put("HOME", tmp.resolve("userhome").toString());

        assertThat(execute("--cd", build.toString(), "-c", "pwd")).isZero();
        assertThat(text(stdout).strip()).isEqualTo(build.toString());
        stdout.reset();
        assertThat(execute("--cd", "build", "-c", "pwd")).isZero();
        assertThat(text(stdout).strip()).isEqualTo(build.toString());
        stdout.reset();
        assertThat(execute("--cd=~/docs", "-c", "pwd")).isZero();
        assertThat(text(stdout).strip()).isEqualTo(docs.toString());
        stdout.reset();
        assertThat(execute("--cd", "~", "-c", "pwd")).isZero();
        assertThat(text(stdout).strip()).isEqualTo(tmp.resolve("userhome").toString());
    }

    @Test
    void thePathsOfTheSessionStartFromTheStartDirectory() throws Exception {
        Path build = directory("build");
        Files.writeString(build.resolve("here.txt"), "x");

        assertThat(execute("--cd=build", "-c", "$pwd")).isZero();
        assertThat(text(stdout).strip()).isEqualTo(build.toString());
        stdout.reset();
        assertThat(execute("--cd", "build", "-c", "ls")).isZero();
        assertThat(text(stdout)).contains("here.txt");
        stdout.reset();
        assertThat(execute("--cd", "build", "-c", "\"hello\" > out.txt")).isZero();
        assertThat(build.resolve("out.txt")).exists();
        assertThat(tmp.resolve("work").resolve("out.txt")).doesNotExist();
    }

    @Test
    void aNativeCommandRunsInTheStartDirectory() throws Exception {
        assumeThat(Path.of("/bin/sh")).exists();
        variables.put("PATH", "/bin:/usr/bin");
        Path build = directory("build");

        // The output of a native command goes to the real standard output, not to the test's: a redirection catches it.
        assertThat(execute("--cd", "build", "-c", "sh -c pwd > native.txt")).isZero();

        assertThat(Path.of(Files.readString(build.resolve("native.txt")).strip()).toRealPath())
                .isEqualTo(build.toRealPath());
    }

    @Test
    void theScriptFileIsLookedUpWhereYouTypedItAndRunsInTheStartDirectory() throws Exception {
        Path build = directory("build");
        Files.writeString(tmp.resolve("work").resolve("s.pj"), "pwd\n");

        assertThat(execute("--cd", "build", "s.pj")).isZero();

        assertThat(text(stdout).strip()).isEqualTo(build.toString());
    }

    @Test
    void theStartDirectoryAppliesToTheInteractivePromptAndWorksWithNoProfile() throws Exception {
        Path build = directory("build");
        try (var terminal = new TestTerminal("exit" + TestTerminal.ENTER)) {
            terminals = _ -> terminal.terminal();
            terminal.startTyping();

            assertThat(execute("--no-profile", "--cd", "build")).isZero();

            assertThat(terminal.screen()).contains("PJ " + build);
        }
    }

    @Test
    void aCdInTheProfileStillWins() throws Exception {
        directory("build");
        Path other = directory("other");
        Files.createDirectories(tmp.resolve("home"));
        Files.writeString(tmp.resolve("home").resolve("profile.pj"), "cd " + other + "\n");
        try (var terminal = new TestTerminal("exit" + TestTerminal.ENTER)) {
            terminals = _ -> terminal.terminal();
            terminal.startTyping();

            execute("--cd", "build");

            assertThat(terminal.screen()).contains("PJ " + other);
        }
    }

    @Test
    void aMissingDirectoryGivesOneAndStartsNothing() throws Exception {
        assertThat(execute("--cd", "absent", "-c", "\"never\"")).isEqualTo(1);

        assertThat(text(messages)).contains("--cd: directory not found: " + tmp.resolve("work").resolve("absent"));
        assertThat(text(stdout)).isEmpty();
    }

    @Test
    void aFileInsteadOfADirectoryGivesOne() throws Exception {
        Files.writeString(directory("d").resolve("f.txt"), "x");

        assertThat(execute("--cd=d/f.txt", "-c", "\"never\"")).isEqualTo(1);

        assertThat(text(messages)).contains("--cd: not a directory: " + tmp.resolve("work").resolve("d").resolve("f.txt"));
        assertThat(text(stdout)).isEmpty();
    }

    @Test
    void theStartDirectoryErrorIsInTheLanguageOfTheSettings() throws Exception {
        Files.createDirectories(tmp.resolve("home"));
        Files.writeString(tmp.resolve("home").resolve("config.properties"), "language=fr\n");

        assertThat(execute("--cd", "absent", "-c", "pwd")).isEqualTo(1);

        assertThat(text(messages)).contains("--cd : dossier introuvable : ");
    }

    @Test
    void aStartDirectoryWithoutValueIsAUsageErrorAndHelpStillWins() throws Exception {
        assertThat(execute("--cd")).isEqualTo(2);
        assertThat(text(messages)).contains("usage: powerj").contains("--cd");

        assertThat(execute("--cd", "absent", "--help")).isZero();
    }
}
