package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** What a cmdlet or a provider gets without writing it: the hooks do nothing, no translation. */
class CmdletDefaultsTest {

    private record NoParams() {
    }

    @Test
    void theHooksOfACmdletDoNothingByDefault() {
        Cmdlet<NoParams, String, String> cmdlet = new Cmdlet<>() {
        };

        assertThatCode(() -> {
            cmdlet.begin(new NoParams(), null);
            cmdlet.process(new NoParams(), "input", null);
            cmdlet.end(new NoParams(), null);
        }).doesNotThrowAnyException();
    }

    @Test
    void aProviderIsNotTranslatedAndNeedsNoInitializationByDefault() {
        CmdletProvider provider = List::of;

        assertThat(provider.cmdlets()).isEmpty();
        assertThat(provider.messages(Locale.FRENCH)).isNull();
        assertThatCode(() -> provider.initialize(Path.of("."))).doesNotThrowAnyException();
    }
}
