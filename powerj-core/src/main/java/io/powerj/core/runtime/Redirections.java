package io.powerj.core.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import io.powerj.core.error.PjError;
import io.powerj.core.error.PjException;
import io.powerj.core.format.Values;
import io.powerj.core.i18n.Messages;
import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Token;
import io.powerj.core.nativeexec.NativeRunner;

/**
 * The files of a statement's redirections: {@code >} / {@code >>} for the values, {@code 2>} / {@code 2>>} for the
 * errors (FR-04e). Each is empty when the statement does not redirect that stream.
 *
 * @param out file that receives the values of the last stage
 * @param err file that receives the errors of every stage
 */
public record Redirections(Optional<NativeRunner.FileTarget> out, Optional<NativeRunner.FileTarget> err) {

    /** No redirection. */
    static final Redirections NONE = new Redirections(Optional.empty(), Optional.empty());

    /**
     * The file the last redirection of {@code stream} names, if the pipeline has one. The target is an argument
     * ({@code > $name}, {@code > "a b"}), evaluated now, and relative to the current directory.
     */
    static Optional<NativeRunner.FileTarget> target(Ast.Pipeline pipeline, Token.Stream stream, Evaluator evaluator,
                                                    Session session) throws InterruptedException {
        Ast.Redirect last = null;
        for (Ast.Redirect redirect : pipeline.redirects()) {
            if (redirect.stream() == stream) {
                last = redirect;
            }
        }
        if (last == null) {
            return Optional.empty();
        }
        Path file = session.currentDirectory().resolve(Values.text(evaluator.argument(last.target())));
        return Optional.of(new NativeRunner.FileTarget(file, last.append()));
    }

    /** Only the errors are redirected. */
    static Redirections errors(Optional<NativeRunner.FileTarget> err) {
        return new Redirections(Optional.empty(), err);
    }

    /**
     * The same redirections for a pipeline whose stages all write the errors file: {@code 2>} is truncated once, here,
     * then every process appends to it.
     */
    Redirections withSharedErrors() {
        if (err.isEmpty() || err.get().append()) {
            return this;
        }
        try {
            Files.writeString(err.get().file(), "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PjException(PjError.of(Messages.get("file.writeFailed", err.get().file(), e.getMessage()), e));
        }
        return new Redirections(out, Optional.of(new NativeRunner.FileTarget(err.get().file(), true)));
    }

    /** What the stages that are not the last one see: their values go to the next stage, not to a file. */
    Redirections forIntermediateStages() {
        return new Redirections(Optional.empty(), err);
    }
}
