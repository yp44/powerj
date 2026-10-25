package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.command.Help;
import io.powerj.core.nativeexec.CommandResolver;
import io.powerj.core.nativeexec.Platform;
import io.powerj.core.support.FakeCmdlets;

/** The one rule that gives a command name its meaning (FR-13), and everything that now reads its answer. */
class CommandResolutionTest {

    @TempDir
    Path tmp;

    private final Map<String, Builtin> builtins = new LinkedHashMap<>();
    private Session session;
    private CommandResolution resolution;
    private Interpreter interpreter;
    private final StringWriter out = new StringWriter();

    /** A cmdlet with the name of a built-in command: the built-in command wins (FR-13). */
    @CmdletInfo(name = "cd", category = "Test", summary = "A cmdlet called cd", examples = "cd")
    static final class CdCmdlet implements Cmdlet<CdCmdlet.Params, Void, String> {
        record Params() { }
    }

    @BeforeEach
    void setUp() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse(Platform.isWindows(), "programs are found through PATHEXT there");
        Path bin = Files.createDirectory(tmp.resolve("bin"));
        Path program = Files.createFile(bin.resolve("tool"));
        Files.setPosixFilePermissions(program, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createFile(bin.resolve("items")); // a program with the name of a cmdlet
        Files.setPosixFilePermissions(bin.resolve("items"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        session = new Session(tmp, tmp, Map.of("PATH", bin.toString()));
        builtins.put("cd", (args, s) -> List.of());
        var cmdlets = new java.util.ArrayList<Cmdlet<?, ?, ?>>(FakeCmdlets.all());
        cmdlets.add(new CdCmdlet());
        resolution = new CommandResolution(builtins, CmdletRegistry.of(cmdlets), new CommandResolver());
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), _ -> { }, false), Map.of(),
                CmdletRegistry.of(FakeCmdlets.all()));
    }

    @Test
    void aBuiltinCommandComesFirst() {
        assertThat(resolution.resolve("cd", session)).isInstanceOf(Resolved.BuiltinCall.class);
        assertThat(resolution.cmdlet("cd")).isEmpty(); // the cmdlet of that name is shadowed, as in execution
    }

    @Test
    void aCmdletComesBeforeAProgram() {
        assertThat(resolution.resolve("items", session)).isInstanceOfSatisfying(Resolved.CmdletCall.class,
                call -> assertThat(call.cmdlet().name()).isEqualTo("items"));
    }

    @Test
    void aProgramIsFoundOnThePath() {
        assertThat(resolution.resolve("tool", session)).isInstanceOfSatisfying(Resolved.NativeCall.class,
                call -> assertThat(call.executable()).isEqualTo(tmp.resolve("bin/tool")));
    }

    @Test
    void theCaretSkipsBuiltinCommandsAndCmdlets() {
        assertThat(resolution.resolve("^items", session)).isInstanceOfSatisfying(Resolved.NativeCall.class,
                call -> assertThat(call.executable()).isEqualTo(tmp.resolve("bin/items")));
        assertThat(resolution.resolve("^cd", session)).isEqualTo(new Resolved.Unknown("cd", true));
        assertThat(resolution.resolve("items", true, session)).isInstanceOf(Resolved.NativeCall.class);
    }

    @Test
    void aNameThatIsNothingIsUnknown() {
        assertThat(resolution.resolve("nosuch", session)).isEqualTo(new Resolved.Unknown("nosuch", false));
        assertThat(resolution.resolve("^nosuch", session)).isEqualTo(new Resolved.Unknown("nosuch", true));
        assertThat(resolution.resolve("", session)).isEqualTo(new Resolved.Unknown("", false));
        assertThat(resolution.resolve("^", session)).isEqualTo(new Resolved.Unknown("^", false)); // a lone ^ is a name
    }

    @Test
    void whatIsAnsweredInProcessNeverLooksAtThePath() {
        assertThat(resolution.inProcess("tool")).isEmpty(); // a program: not answered without the file system
        assertThat(resolution.inProcess("items")).isPresent();
        assertThat(resolution.inProcess("cd")).get().isInstanceOf(Resolved.BuiltinCall.class);
    }

    @Test
    void theBuiltinNamesAreTheLiveOnes() {
        builtins.put("late", (args, s) -> List.of());

        assertThat(resolution.builtinNames()).containsExactly("cd", "late");
        assertThat(resolution.resolve("late", session)).isInstanceOf(Resolved.BuiltinCall.class);
    }

    // --- The consumers agree with it ---

    @Test
    void theKindOfACommandFollowsTheResolution() {
        assertThat(interpreter.commandKind("cd")).isEqualTo(CommandKind.BUILTIN);
        assertThat(interpreter.commandKind("items")).isEqualTo(CommandKind.CMDLET);
        assertThat(interpreter.commandKind("tool")).isEqualTo(CommandKind.NATIVE);
        assertThat(interpreter.commandKind("^items")).isEqualTo(CommandKind.NATIVE);
        assertThat(interpreter.commandKind("nosuch")).isEqualTo(CommandKind.UNKNOWN);
        assertThat(interpreter.commandKind("^")).isEqualTo(CommandKind.UNKNOWN);
    }

    @Test
    void whichSaysWhatExecutionWouldRun() throws Exception {
        interpreter.execute("which cd");
        interpreter.execute("which items");
        interpreter.execute("which tool");
        interpreter.execute("which ^items");

        assertThat(out.toString().lines()).containsExactly("cd → built-in command",
                "items → cmdlet ((no module))", "tool → native " + tmp.resolve("bin/tool"),
                "^items → native " + tmp.resolve("bin/items"));
    }

    @Test
    void everyBuiltinCommandOfTheInterpreterIsDocumentedByHelp() {
        for (String name : interpreter.builtinNames()) {
            if (name.equals("history")) {
                continue; // provided by the shell, documented here all the same
            }
            assertThat(Help.isDocumentedBuiltin(name)).as("help.builtin.%s.description", name).isTrue();
        }
        assertThat(Help.isDocumentedBuiltin("history")).isTrue();
    }

    @Test
    void theOverviewListsTheBuiltinCommandsInTheOrderOfTheInterpreter() throws Exception {
        interpreter.execute("help");

        List<String> lines = out.toString().lines().takeWhile(line -> !line.isBlank()).skip(1).toList();
        assertThat(lines).extracting(line -> line.strip().split("\\s+")[0])
                .containsExactly("cd", "pwd", "which", "help", "import", "mod-load", "mod-list", "jobs", "exit");
    }
}
