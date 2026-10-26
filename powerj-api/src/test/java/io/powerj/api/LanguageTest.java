package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ListResourceBundle;
import java.util.Locale;
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
    void resolutionFollowsThePriorityPropertyEnvironmentConfigurationSystem() {
        assertThat(Language.resolve("fr", "en", "en", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(null, "fr", "en", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(" ", "", "fr", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(null, null, null, Locale.FRENCH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(null, null, null, Locale.ENGLISH)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void anyLanguageButFrenchGivesEnglish() {
        assertThat(Language.resolve("de", null, null, Locale.FRENCH)).isEqualTo(Locale.ENGLISH);
        assertThat(Language.resolve(null, null, null, Locale.GERMAN)).isEqualTo(Locale.ENGLISH);
        assertThat(Language.resolve(null, null, null, null)).isEqualTo(Locale.ENGLISH);
        assertThat(Language.resolve("FR_ca", null, null, Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
    }

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

    @Test
    void theLanguageCanBeSetAndIsNormalized() {
        Locale before = Language.current();
        try {
            Language.set(Locale.GERMANY);
            assertThat(Language.current()).isEqualTo(Locale.ENGLISH);
            Language.set(Locale.CANADA_FRENCH);
            assertThat(Language.current()).isEqualTo(Locale.FRENCH);
            Language.set(null);
            assertThat(Language.current()).isEqualTo(Locale.ENGLISH);
        } finally {
            Language.set(before);
        }
    }
}
