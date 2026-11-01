package io.powerj.api.internal;

import io.powerj.api.Language;

import java.util.Locale;

/**
 * Where the language of PowerJ is chosen and kept. <b>Not part of the API</b>: this package is exported to the built-in
 * modules only (see {@code module-info.java}), so a third-party cmdlet can read the language ({@link Language#current()})
 * but cannot change it. The shell writes it once, at startup (specification FR-61); the tests of the built-in modules
 * write it to check both languages.
 */
public final class LanguageSetting {

    private static volatile Locale current;

    private LanguageSetting() {
    }

    /**
     * {@link Locale#ENGLISH} or {@link Locale#FRENCH}. When nothing has been set (tests, embedding), it is resolved on
     * first use from the system property and the language of the system.
     */
    public static Locale current() {
        Locale locale = current;
        if (locale == null) {
            locale = resolve(System.getProperty(Language.PROPERTY), null, null,
                    Locale.getDefault(Locale.Category.DISPLAY));
            current = locale;
        }
        return locale;
    }

    /** Sets the language (normalized to English or French). */
    public static void set(Locale locale) {
        current = normalize(locale == null ? null : locale.getLanguage());
    }

    /**
     * Language chosen from, in order of priority, the system property, the environment variable, the
     * configuration file and the system locale; blank values are ignored.
     */
    public static Locale resolve(String property, String environment, String configuration, Locale system) {
        for (String value : new String[] {property, environment, configuration}) {
            if (value != null && !value.isBlank()) {
                return normalize(value.strip());
            }
        }
        return normalize(system == null ? null : system.getLanguage());
    }

    private static Locale normalize(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("fr") ? Locale.FRENCH : Locale.ENGLISH;
    }
}
