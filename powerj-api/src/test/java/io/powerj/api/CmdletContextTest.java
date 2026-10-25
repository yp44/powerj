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
    };

    @Test
    void theOptionalCapabilitiesAreAbsentByDefault() {
        assertThat(MINIMAL.hasInput()).isFalse();
        assertThat(MINIMAL.configDirectory()).isEmpty();
        assertThat(MINIMAL.perfReports()).isEmpty();
        MINIMAL.stopInput(); // no effect, no failure
    }

    @Test
    void aContextThatKeepsNoStateSaysSoInsteadOfLosingIt() {
        // A silent default (a new state at each call) would make a counter restart at every object.
        assertThatThrownBy(() -> MINIMAL.state(StringBuilder.class, StringBuilder::new))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("no execution state");
    }
}
