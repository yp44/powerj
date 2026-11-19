package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.error.PjException;
import io.powerj.core.support.FakeCmdlets;

/**
 * {@code 2>} is truncated once by the statement: a blocking error of the last stage is appended to the file, it does not
 * erase the errors of the earlier stages (FR-49, audit v2 R-05, #168).
 */
class BlockingErrorRedirectionTest {

    /** Fails when its input is over, as a cmdlet that cannot finish its work does. */
    @CmdletInfo(name = "boom", category = "Test", summary = "Fails at the end", examples = "items | boom")
    static final class Boom implements Cmdlet<Boom.Params, Object, Object> {

        record Params() { }

        @Override
        public void end(Params params, CmdletContext<Object> context) {
            throw new PjException("boom: cannot finish");
        }
    }

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        var cmdlets = new ArrayList<Cmdlet<?, ?, ?>>(FakeCmdlets.all());
        cmdlets.add(new Boom());
        interpreter = new Interpreter(new Session(tmp, tmp, Map.of("PATH", "")),
                new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(), CmdletRegistry.of(cmdlets));
    }

    private List<String> lines(String file) throws Exception {
        return Files.readAllLines(tmp.resolve(file));
    }

    @Test
    void aBlockingErrorOfTheLastStageKeepsTheErrorsOfTheEarlierOnes() throws Exception {
        interpreter.execute("items --fail | boom 2> e.txt");

        assertThat(lines("e.txt")).containsExactly("items: problem with item1", "boom: cannot finish");
        assertThat(errors).isEmpty();
    }

    @Test
    void aBlockingErrorAloneStillTruncatesTheFileOfAnEarlierLine() throws Exception {
        Files.writeString(tmp.resolve("e.txt"), "old line\n");

        interpreter.execute("items | boom 2> e.txt");

        assertThat(lines("e.txt")).containsExactly("boom: cannot finish");
    }

    @Test
    void withAppendTheOldLinesStay() throws Exception {
        Files.writeString(tmp.resolve("e.txt"), "old line\n");

        interpreter.execute("items --fail | boom 2>> e.txt");

        assertThat(lines("e.txt")).containsExactly("old line", "items: problem with item1", "boom: cannot finish");
    }
}
