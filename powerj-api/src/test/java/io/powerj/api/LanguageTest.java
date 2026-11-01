package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ListResourceBundle;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

class LanguageTest {

    private static final ResourceBundle BUNDLE = new ListResourceBundle() {
        @Override
        protected Object[][] getContents() {
            return new Object[][] {{"hello", "Hello {0}!"}, {"two", "{1}-{0}"}, {"plain", "it's {braces}"},
                    {"quoted", "'{0}'"}};
        }
    };

    @Test
    void placeholdersAreReplacedByPosition() {
        assertThat(Language.text(BUNDLE, "hello", "world")).isEqualTo("Hello world!");
        assertThat(Language.text(BUNDLE, "two", "a", "b")).isEqualTo("b-a");
    }

    @Test
    void aPlaceholderWithoutArgumentIsKeptAndApostrophesAreNotEscapes() {
        assertThat(Language.text(BUNDLE, "two", "only")).isEqualTo("{1}-only");
        assertThat(Language.text(BUNDLE, "plain", "x")).isEqualTo("it's {braces}");
        assertThat(Language.text(BUNDLE, "quoted", "x")).isEqualTo("'x'");
    }

    @Test
    void nullArgumentsAreShownAndMissingKeysGiveTheKey() {
        assertThat(Language.text(BUNDLE, "hello", (Object) null)).isEqualTo("Hello null!");
        assertThat(Language.text(BUNDLE, "absent", "x")).isEqualTo("absent");
        assertThat(Language.text(null, "any.key")).isEqualTo("any.key");
    }
}
