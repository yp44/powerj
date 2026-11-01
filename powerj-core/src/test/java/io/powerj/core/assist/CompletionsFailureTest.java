package io.powerj.core.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.CommandCatalog;
import io.powerj.core.runtime.CommandKind;
import io.powerj.core.runtime.Resolved;
import io.powerj.core.runtime.Session;

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
        Logger logger = Logger.getLogger(Completions.class.getName());
        List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Level before = logger.getLevel();
        logger.setLevel(Level.FINE);
        handler.setLevel(Level.FINE);
        logger.addHandler(handler);
        try {
            var result = new Completions(new BrokenCatalog()).complete("ls .h", 5);
            assertThat(result.candidates()).isEmpty();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(before);
        }
        assertThat(records).anySatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.FINE);
            assertThat(record.getThrown()).hasMessage("the session is gone");
        });
    }
}
