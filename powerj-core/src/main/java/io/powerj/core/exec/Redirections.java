package io.powerj.core.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * The files of a statement's redirections: {@code >} / {@code >>} for the values, {@code 2>} / {@code 2>>} for the
 * errors (FR-04e). Each is empty when the statement does not redirect that stream.
 *
 * @param out file that receives the values of the last stage
 * @param err file that receives the errors of every stage
 */
record Redirections(Optional<NativeRunner.FileTarget> out, Optional<NativeRunner.FileTarget> err) {

    /** No redirection. */
    static final Redirections NONE = new Redirections(Optional.empty(), Optional.empty());

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
