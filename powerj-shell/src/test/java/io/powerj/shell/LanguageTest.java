package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

import io.powerj.api.Language;

/** Choice of the message language and formatting of the texts (FR-61). */
class LanguageTest {

    @Test
    void resolutionOrderIsPropertyEnvironmentConfigurationSystem() {
        assertThat(Language.resolve("fr", "en", "en", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve("en", "fr", "fr", Locale.FRENCH)).isEqualTo(Locale.ENGLISH);
        assertThat(Language.resolve(null, "fr", "en", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(null, "en", "fr", Locale.FRENCH)).isEqualTo(Locale.ENGLISH);
        assertThat(Language.resolve(null, null, "fr", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(null, null, "en", Locale.FRENCH)).isEqualTo(Locale.ENGLISH);
        assertThat(Language.resolve(null, null, null, Locale.FRENCH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(null, null, null, Locale.ENGLISH)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void blankValuesAreIgnored() {
        assertThat(Language.resolve("", "  ", "fr", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(" ", "", "", Locale.FRENCH)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(" ", null, "\t", Locale.ENGLISH)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void valuesAreNormalizedToEnglishOrFrench() {
        for (String french : new String[] {"fr", "fr_FR", "FR", " fr "}) {
            assertThat(Language.resolve(french, null, null, Locale.ENGLISH)).as(french).isEqualTo(Locale.FRENCH);
        }
        assertThat(Language.resolve("de", null, null, Locale.FRENCH)).isEqualTo(Locale.ENGLISH);
        assertThat(Language.resolve(null, null, null, null)).isEqualTo(Locale.ENGLISH);
        assertThat(Language.resolve(null, null, null, Locale.GERMAN)).isEqualTo(Locale.ENGLISH);
        assertThat(Language.resolve(null, null, null, Locale.FRANCE)).isEqualTo(Locale.FRENCH);
        assertThat(Language.resolve(null, null, null, Locale.CANADA_FRENCH)).isEqualTo(Locale.FRENCH);
    }

    @Test
    void setNormalizesAndCurrentReflectsIt() {
        try {
            Language.set(Locale.FRANCE);
            assertThat(Language.current()).isEqualTo(Locale.FRENCH);
            Language.set(Locale.GERMAN);
            assertThat(Language.current()).isEqualTo(Locale.ENGLISH);
        } finally {
            Language.set(Locale.ENGLISH);
        }
    }

    private static ResourceBundle bundle(String text) throws IOException {
        return new PropertyResourceBundle(new StringReader(text));
    }

    @Test
    void textSubstitutesPlaceholders() throws IOException {
        var bundle = bundle("one=unknown command: {0}\ntwo={1} then {0}, {0} again\n");
        assertThat(Language.text(bundle, "one", "x")).isEqualTo("unknown command: x");
        assertThat(Language.text(bundle, "two", "a", 2)).isEqualTo("2 then a, a again");
        assertThat(Language.text(bundle, "one", (Object) null)).isEqualTo("unknown command: null");
        assertThat(Language.text(bundle, "one")).isEqualTo("unknown command: {0}");
    }

    @Test
    void missingKeyOrBundleGivesTheKey() throws IOException {
        assertThat(Language.text(bundle("a=b\n"), "absent.key", "x")).isEqualTo("absent.key");
        assertThat(Language.text(null, "some.key")).isEqualTo("some.key");
    }

    @Test
    void apostrophesAndBracesAreKept() throws IOException {
        var bundle = bundle("q=l'option '{0}' n'existe pas\nb=block { $_ } and {x} and {0}\n");
        assertThat(Language.text(bundle, "q", "-x")).isEqualTo("l'option '-x' n'existe pas");
        assertThat(Language.text(bundle, "b", "y")).isEqualTo("block { $_ } and {x} and y");
    }

    @Test
    void unknownPlaceholderIndexIsLeftAsIs() throws IOException {
        var bundle = bundle("k={0} and {1} and {7}\n");
        assertThat(Language.text(bundle, "k", "a")).isEqualTo("a and {1} and {7}");
    }
}
