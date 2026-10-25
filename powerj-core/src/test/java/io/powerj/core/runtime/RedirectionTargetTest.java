package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.Syntax;
import io.powerj.core.lang.Token;
import io.powerj.core.nativeexec.NativeRunner;

/** The file a redirection names: an argument, evaluated when the statement starts. */
class RedirectionTargetTest {

    @TempDir
    Path tmp;

    private Session session;
    private Evaluator evaluator;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, Map.of());
        evaluator = new Evaluator(session, _ -> {
            throw new UnsupportedOperationException("no subexpression in these tests");
        }, () -> new Syntax(_ -> false, _ -> false));
    }

    private Optional<NativeRunner.FileTarget> target(String line, Token.Stream stream) throws InterruptedException {
        Ast.Pipeline pipeline = Parser.parse(line, _ -> false).steps().getFirst().statement().pipeline();
        return Redirections.target(pipeline, stream, evaluator, session);
    }

    @Test
    void aPipelineWithoutRedirectionHasNoTarget() throws Exception {
        assertThat(target("items", Token.Stream.OUT)).isEmpty();
        assertThat(target("items", Token.Stream.ERR)).isEmpty();
    }

    @Test
    void theValuesAndTheErrorsHaveTheirOwnFile() throws Exception {
        String line = "items > out.txt 2> err.txt";

        assertThat(target(line, Token.Stream.OUT)).contains(new NativeRunner.FileTarget(tmp.resolve("out.txt"), false));
        assertThat(target(line, Token.Stream.ERR)).contains(new NativeRunner.FileTarget(tmp.resolve("err.txt"), false));
    }

    @Test
    void aDoubleArrowAppends() throws Exception {
        assertThat(target("items >> out.txt", Token.Stream.OUT)).contains(new NativeRunner.FileTarget(tmp.resolve("out.txt"), true));
        assertThat(target("items 2>> err.txt", Token.Stream.ERR)).contains(new NativeRunner.FileTarget(tmp.resolve("err.txt"), true));
    }

    @Test
    void theLastRedirectionOfAStreamWins() throws Exception {
        assertThat(target("items > first.txt > second.txt", Token.Stream.OUT))
                .contains(new NativeRunner.FileTarget(tmp.resolve("second.txt"), false));
    }

    @Test
    void theTargetIsRelativeToTheCurrentDirectoryOfTheSession() throws Exception {
        Path other = tmp.resolve("sub");
        java.nio.file.Files.createDirectory(other);
        session.changeDirectory(other);

        assertThat(target("items > out.txt", Token.Stream.OUT)).contains(new NativeRunner.FileTarget(other.resolve("out.txt"), false));
    }

    @Test
    void theTargetCanBeAnExpression() throws Exception {
        session.setVariable("name", "from-variable.txt");

        assertThat(target("items > $name", Token.Stream.OUT))
                .contains(new NativeRunner.FileTarget(tmp.resolve("from-variable.txt"), false));
    }
}
