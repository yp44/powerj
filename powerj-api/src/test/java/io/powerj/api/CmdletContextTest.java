package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** What a context that implements only the required methods says for the others. */
class CmdletContextTest {

    private static final CmdletContext<String> MINIMAL = new CmdletContext<>() {
        @Override
        public void emit(String value) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public Path currentDirectory() {
            return Path.of(".");
        }

        @Override
        public Map<String, String> environment() {
            return Map.of();
        }

        @Override
        public Optional<Object> variable(String name) {
            return Optional.empty();
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public ScriptBlock compile(String expression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object readProperty(Object target, String name) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void theOptionalCapabilitiesAreAbsentByDefault() {
        assertThat(MINIMAL.hasInput()).isFalse();
        assertThat(MINIMAL.configDirectory()).isEmpty();
        assertThat(MINIMAL.service(io.powerj.api.perf.PerfReports.class)).isEmpty();
        MINIMAL.stopInput(); // no effect, no failure
    }

    @Test
    void aContextThatKeepsNoStateSaysSoInsteadOfLosingIt() {
        // A silent default (a new state at each call) would make a counter restart at every object.
        assertThatThrownBy(() -> MINIMAL.state(StringBuilder.class, StringBuilder::new))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("no execution state");
    }

    @Test
    void aContextThatCannotChangeTheEnvironmentSaysSoInsteadOfIgnoringTheChange() {
        assertThatThrownBy(() -> MINIMAL.setEnvironment("X", "1")).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("environment");
        assertThatThrownBy(() -> MINIMAL.unsetEnvironment("X")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aUsageExceptionIsAnIllegalArgumentExceptionWithItsMessageAndCause() {
        var cause = new RuntimeException("why");

        assertThat(new UsageException("bad option")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bad option").hasNoCause();
        assertThat(new UsageException("bad option", cause)).hasCause(cause);
    }

    /** A context whose {@code readProperty} is the one given: what {@code tryReadProperty} adds to it is under test. */
    private static CmdletContext<String> reading(java.util.function.BiFunction<Object, String, Object> reader) {
        return new CmdletContext<>() {
            @Override
            public void emit(String value) {
            }

            @Override
            public void error(String message) {
            }

            @Override
            public Path currentDirectory() {
                return Path.of(".");
            }

            @Override
            public Map<String, String> environment() {
                return Map.of();
            }

            @Override
            public Optional<Object> variable(String name) {
                return Optional.empty();
            }

            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public ScriptBlock compile(String expression) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object readProperty(Object target, String name) {
                return reader.apply(target, name);
            }
        };
    }

    @Test
    void tryReadPropertyReturnsTheValueOfReadProperty() {
        var attempt = reading((target, name) -> name + " of " + target).tryReadProperty("object", "size");

        assertThat(attempt.failed()).isFalse();
        assertThat(attempt.value()).isEqualTo("size of object");
    }

    @Test
    void tryReadPropertyReturnsTheFailureAsAValue() {
        var failure = new IllegalStateException("cannot read");
        var context = reading((target, name) -> {
            throw failure;
        });

        var attempt = context.tryReadProperty("object", "size");

        assertThat(attempt.failed()).isTrue();
        assertThat(attempt.error()).isSameAs(failure);
    }

    @Test
    void tryReadPropertyNeverTakesTheInterruptionForAnUnreadableObject() {
        var context = reading((target, name) -> {
            throw new java.util.concurrent.CancellationException("Ctrl+C");
        });

        assertThatThrownBy(() -> context.tryReadProperty("object", "size"))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }
}
