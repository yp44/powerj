package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.error.PjException;

/** The redirections of a statement and the context a stage runs in (Phase 2.2). */
class RedirectionsTest {

    @TempDir
    Path tmp;

    @Test
    void noRedirectionIsNone() {
        assertThat(Redirections.NONE.out()).isEmpty();
        assertThat(Redirections.NONE.err()).isEmpty();
        assertThat(Redirections.NONE.withSharedErrors()).isSameAs(Redirections.NONE);
    }

    @Test
    void sharingTheErrorsFileTruncatesItOnceAndMakesEveryStageAppend() throws Exception {
        Path file = Files.writeString(tmp.resolve("errors.txt"), "old content\n");
        var redirections = Redirections.errors(Optional.of(new NativeRunner.FileTarget(file, false)));

        var shared = redirections.withSharedErrors();

        assertThat(file).hasContent("");
        assertThat(shared.err()).contains(new NativeRunner.FileTarget(file, true));
    }

    @Test
    void anErrorsFileThatAppendsIsLeftAlone() throws Exception {
        Path file = Files.writeString(tmp.resolve("errors.txt"), "kept\n");
        var redirections = Redirections.errors(Optional.of(new NativeRunner.FileTarget(file, true)));

        assertThat(redirections.withSharedErrors()).isSameAs(redirections);
        assertThat(file).hasContent("kept\n");
    }

    @Test
    void sharingAnErrorsFileThatCannotBeWrittenIsABlockingError() {
        Path folder = tmp; // a folder cannot be written as a file
        var redirections = Redirections.errors(Optional.of(new NativeRunner.FileTarget(folder, false)));

        assertThatThrownBy(redirections::withSharedErrors).isInstanceOf(PjException.class);
    }

    @Test
    void theStagesBeforeTheLastDoNotWriteTheValuesFile() {
        var out = new NativeRunner.FileTarget(tmp.resolve("out.txt"), false);
        var err = new NativeRunner.FileTarget(tmp.resolve("err.txt"), true);

        var intermediate = new Redirections(Optional.of(out), Optional.of(err)).forIntermediateStages();

        assertThat(intermediate.out()).isEmpty();
        assertThat(intermediate.err()).contains(err);
    }

    @Test
    void anIntermediateContextKeepsTheSurroundingsAndLosesTheDestinationOfValues() {
        var session = new Session(tmp, tmp, Map.of());
        var io = new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false);
        var err = new NativeRunner.FileTarget(tmp.resolve("err.txt"), true);
        var last = new ExecutionContext(session, io, _ -> { }, true,
                new Redirections(Optional.of(new NativeRunner.FileTarget(tmp.resolve("out.txt"), false)),
                        Optional.of(err)),
                _ -> { }, true);

        var middle = last.forIntermediateStage();

        assertThat(middle.sink()).isNull();
        assertThat(middle.capture()).isFalse();
        assertThat(middle.redirections().out()).isEmpty();
        assertThat(middle.redirections().err()).contains(err);
        assertThat(middle.session()).isSameAs(session);
        assertThat(middle.io()).isSameAs(io);
        assertThat(middle.errors()).isSameAs(last.errors());
        assertThat(middle.inheritInput()).isTrue();
    }
}
