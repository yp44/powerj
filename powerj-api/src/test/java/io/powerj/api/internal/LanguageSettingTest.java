package io.powerj.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class LanguageSettingTest {

    @Test
    void resolutionFollowsThePriorityPropertyEnvironmentConfigurationSystem() {
        assertThat(LanguageSetting.resolve("fr", "en", "en", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(LanguageSetting.resolve(null, "fr", "en", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(LanguageSetting.resolve(" ", "", "fr", Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
        assertThat(LanguageSetting.resolve(null, null, null, Locale.FRENCH)).isEqualTo(Locale.FRENCH);
        assertThat(LanguageSetting.resolve(null, null, null, Locale.ENGLISH)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void anyLanguageButFrenchGivesEnglish() {
        assertThat(LanguageSetting.resolve("de", null, null, Locale.FRENCH)).isEqualTo(Locale.ENGLISH);
        assertThat(LanguageSetting.resolve(null, null, null, Locale.GERMAN)).isEqualTo(Locale.ENGLISH);
        assertThat(LanguageSetting.resolve(null, null, null, null)).isEqualTo(Locale.ENGLISH);
        assertThat(LanguageSetting.resolve("FR_ca", null, null, Locale.ENGLISH)).isEqualTo(Locale.FRENCH);
    }

    @Test
    void theLanguageCanBeSetAndIsNormalized() {
        Locale before = LanguageSetting.current();
        try {
            LanguageSetting.set(Locale.GERMANY);
            assertThat(LanguageSetting.current()).isEqualTo(Locale.ENGLISH);
            LanguageSetting.set(Locale.CANADA_FRENCH);
            assertThat(LanguageSetting.current()).isEqualTo(Locale.FRENCH);
            LanguageSetting.set(null);
            assertThat(LanguageSetting.current()).isEqualTo(Locale.ENGLISH);
        } finally {
            LanguageSetting.set(before);
        }
    }

    @Test
    void whatIsSetIsWhatACmdletReads() {
        Locale before = LanguageSetting.current();
        try {
            LanguageSetting.set(Locale.FRENCH);
            assertThat(io.powerj.api.Language.current()).isEqualTo(Locale.FRENCH);
            LanguageSetting.set(Locale.ENGLISH);
            assertThat(io.powerj.api.Language.current()).isEqualTo(Locale.ENGLISH);
        } finally {
            LanguageSetting.set(before);
        }
    }
}
