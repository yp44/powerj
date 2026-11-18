package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.support.FakeCmdlets;
import io.powerj.fs.Host;

/** A mistyped command gets the hint an option gets (FR-13, audit v2 U-02): one name, never a guess between several. */
class UnknownCommandTest {

    @TempDir
    Path tmp;

    private final List<String> errors = new ArrayList<>();
    private Interpreter interpreter;
    private Path bin;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(Host.windows(), "programs are found through PATHEXT there");
        bin = Files.createDirectory(tmp.resolve("bin"));
        Path program = Files.createFile(bin.resolve("toolbox"));
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwxr-xr-x"));
        var session = new Session(tmp, tmp, Map.of("PATH", bin.toString()));
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter(), true), errors::add, false),
                Map.of(), CmdletRegistry.of(FakeCmdlets.all()));
    }

    private String error(String line) throws Exception {
        errors.clear();
        interpreter.execute(line);
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }

    @Test
    void aTypoOfACmdletSuggestsIt() throws Exception {
        assertThat(error("itmes")).isEqualTo("unknown command: itmes, did you mean items?");
        assertThat(error("filtr")).isEqualTo("unknown command: filtr, did you mean filter?");
    }

    @Test
    void aTypoOfABuiltInCommandSuggestsIt() throws Exception {
        assertThat(error("whcih items")).isEqualTo("unknown command: whcih, did you mean which?");
    }

    @Test
    void theBeginningOfOneNameSuggestsIt() throws Exception {
        assertThat(error("ite")).isEqualTo("unknown command: ite, did you mean items?");
    }

    @Test
    void severalNamesThatBeginAlikeSuggestNothing() throws Exception {
        assertThat(error("ev")).isEqualTo("unknown command: ev"); // eval, eval-each
    }

    @Test
    void aNameThatIsNearNothingSuggestsNothing() throws Exception {
        assertThat(error("xyzzy")).isEqualTo("unknown command: xyzzy");
    }

    @Test
    void theCaseIsOnlyAHint() throws Exception {
        assertThat(error("Items")).isEqualTo("unknown command: Items, did you mean items?");
    }

    @Test
    void aProgramIsSuggestedOnlyOnceTheCompletionHasSeenThePath() throws Exception {
        assertThat(error("tolbox")).isEqualTo("unknown command: tolbox"); // no scan for an error: the cache is empty

        interpreter.nativeCommands().names(Map.of("PATH", bin.toString())); // what Tab does

        assertThat(error("tolbox")).isEqualTo("unknown command: tolbox, did you mean toolbox?");
    }

    @Test
    void aForcedProgramKeepsItsOwnMessage() throws Exception {
        assertThat(error("^itmes")).doesNotContain("did you mean");
    }
}
