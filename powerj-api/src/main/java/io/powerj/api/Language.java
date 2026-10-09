package io.powerj.api;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Language of the messages displayed by PowerJ and its cmdlets: English or French (specification FR-61).
 * <p>
 * Resolution order: system property {@value #PROPERTY}, environment variable {@code POWERJ_LANG}, key
 * {@code language} of {@code config.properties}, then the language of the system. Any language other than
 * French gives English. The shell resolves it at startup ({@link #set}); otherwise (tests, embedding) it is
 * resolved on first use from the system property and the system locale.
 * <p>
 * A module translates its texts with its own {@link ResourceBundle} files {@code messages_en.properties}
 * and {@code messages_fr.properties}, loaded from its own module with {@code ResourceBundle.getBundle(name,
 * Language.current())}, and formats them with {@link #text}.
 */
public final class Language {

    /** System property that forces the language ({@code en} or {@code fr}). */
    public static final String PROPERTY = "powerj.language";

    /** Environment variable that chooses the language ({@code en} or {@code fr}). */
    public static final String ENVIRONMENT_VARIABLE = "POWERJ_LANG";

    private static volatile Locale current;

    private Language() {
    }

    /** {@link Locale#ENGLISH} or {@link Locale#FRENCH}. */
    public static Locale current() {
        Locale locale = current;
        if (locale == null) {
            locale = resolve(System.getProperty(PROPERTY), null, null, Locale.getDefault(Locale.Category.DISPLAY));
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

    /**
     * Text of {@code key} in {@code bundle}, with {@code {0}}, {@code {1}}… replaced by the arguments (no
     * {@link java.text.MessageFormat} escaping: apostrophes are written as is). A missing bundle or key
     * gives the key itself.
     */
    public static String text(ResourceBundle bundle, String key, Object... args) {
        String pattern;
        try {
            pattern = bundle == null ? key : bundle.getString(key);
        } catch (MissingResourceException e) {
            pattern = key;
        }
        if (args.length == 0 || pattern.indexOf('{') < 0) {
            return pattern;
        }
        StringBuilder text = new StringBuilder(pattern.length() + 32);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            int close = c == '{' ? pattern.indexOf('}', i) : -1;
            if (close > i + 1 && pattern.substring(i + 1, close).chars().allMatch(Character::isDigit)) {
                int index = Integer.parseInt(pattern.substring(i + 1, close));
                text.append(index < args.length ? String.valueOf(args[index]) : pattern.substring(i, close + 1));
                i = close;
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }
}
