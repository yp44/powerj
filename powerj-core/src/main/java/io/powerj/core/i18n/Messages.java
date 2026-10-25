package io.powerj.core.i18n;

import java.util.ResourceBundle;

import io.powerj.api.Language;

/** Messages of the {@code io.powerj.core.exec} package, in the current {@link Language} (FR-61). */
public final class Messages {

    private static final String BUNDLE = "io.powerj.core.i18n.messages";

    private Messages() {
    }

    /** Does the bundle define {@code key}? (the other methods answer the key itself when it is missing) */
    public static boolean has(String key) {
        return ResourceBundle.getBundle(BUNDLE, Language.current()).containsKey(key);
    }

    /** Text of {@code key} with {@code {0}}, {@code {1}}… replaced by {@code args}. */
    public static String get(String key, Object... args) {
        return Language.text(ResourceBundle.getBundle(BUNDLE, Language.current()), key, args);
    }
}
