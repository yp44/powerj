package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.powerj.core.BuildInfo;

class ReplTest {

    private static final BuildInfo BUILD = new BuildInfo("0.1.0", Runtime.Version.parse("27"));
    private static final Path CWD = Path.of("/home/yves").toAbsolutePath();

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String input) {
        var repl = new Repl(new BufferedReader(new StringReader(input)),
                new PrintWriter(out), new PrintWriter(err), () -> CWD);
        return repl.run(BUILD);
    }

    @Test
    void showsBannerThenPromptWithCurrentDirectory() {
        run("exit\n");

        assertThat(out.toString())
                .startsWith("PowerJ 0.1.0 (Java 27)" + System.lineSeparator())
                .contains("PJ " + CWD + "> ");
    }

    @Test
    void exitStopsTheLoopWithItsCode() {
        assertThat(run("exit\nnever-read\n")).isZero();
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void exitCodeIsReturned() {
        assertThat(run("exit 3\n")).isEqualTo(3);
    }

    @Test
    void endOfInputExitsWithZero() {
        assertThat(run("")).isZero();
    }

    @Test
    void unknownCommandIsReportedAndLoopContinues() {
        int code = run("bonjour\n\nexit 2\n");

        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).isEqualTo("commande inconnue : bonjour" + System.lineSeparator());
        assertThat(out.toString().split("PJ ", -1)).hasSize(4); // un prompt par ligne lue
    }
}
