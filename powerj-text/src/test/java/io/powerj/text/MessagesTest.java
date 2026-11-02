package io.powerj.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

/** The texts of the module (FR-61): a bundle per language, read from the module itself. */
class MessagesTest {

    private static final String BUNDLE = "io.powerj.text.messages";

    @Test
    void bothLanguagesHaveABundleInTheModule() {
        assertThat(ResourceBundle.getBundle(BUNDLE, Locale.ENGLISH)).isNotNull();
        assertThat(ResourceBundle.getBundle(BUNDLE, Locale.FRENCH)).isNotNull();
    }

    @Test
    void aKeyThatIsNotThereGivesTheKeyItself() {
        assertThat(Messages.has("no.such.key")).isFalse();
        assertThat(Messages.get("no.such.key")).isEqualTo("no.such.key");
        assertThat(Messages.get("no.such.key", "argument")).isEqualTo("no.such.key");
    }
}
