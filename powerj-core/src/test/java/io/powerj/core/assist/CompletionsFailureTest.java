package io.powerj.core.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.CommandCatalog;
import io.powerj.core.runtime.CommandKind;
import io.powerj.core.runtime.Resolved;
import io.powerj.core.runtime.Session;
import io.powerj.core.support.LogCapture;

/** Completion never breaks the input, and never hides why it gave up (#82). */
class CompletionsFailureTest {

    /** A catalog whose session cannot be read: any completion that needs it fails. */
    private static final class BrokenCatalog implements CommandCatalog {
        @Override
        public Session session() {
            throw new IllegalStateException("the session is gone");
        }

        @Override
        public Set<String> builtinNames() {
            return Set.of();
        }

        @Override
        public Collection<CmdletRegistry.Registered> cmdlets() {
            return List.of();
        }

        @Override
        public Optional<CmdletRegistry.Registered> cmdlet(String name) {
            return Optional.empty();
        }

        @Override
        public Resolved resolve(String name) {
            return new Resolved.Unknown(name, false);
        }

        @Override
        public CommandKind commandKind(String name) {
            return CommandKind.UNKNOWN;
        }
    }

    @Test
    void aFailureGivesNoCandidatesAndIsLogged() {
        try (var log = new LogCapture(Completions.class)) {
            var result = new Completions(new BrokenCatalog()).complete("ls .h", 5);

            assertThat(result.candidates()).isEmpty();
            assertThat(log.records()).anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.FINE);
                assertThat(record.getThrown()).hasMessage("the session is gone");
            });
        }
    }

    /** The line is the one being typed, partial and maybe with a secret: the log holds the name of the command only (#91). */
    @Test
    void theLogHoldsTheNameOfTheCommandNotTheLineBeingTyped() {
        String line = " curl -u user:s3cret https://exam";
        try (var log = new LogCapture(Completions.class)) {
            new Completions(new BrokenCatalog()).complete(line, line.length());

            assertThat(log.text()).contains("Completion failed on: curl [truncated...]", "the session is gone")
                    .doesNotContain("s3cret", "user:", "exam");
        }
    }
}
