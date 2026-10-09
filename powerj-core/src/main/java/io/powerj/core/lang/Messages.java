package io.powerj.core.lang;

import java.util.ResourceBundle;

import io.powerj.api.Language;

/** Messages of the {@code io.powerj.core.lang} package, in the current {@link Language} (FR-61). */
final class Messages {

    private static final String BUNDLE = "io.powerj.core.lang.messages";

    private Messages() {
    }

    /** Text of {@code key} with {@code {0}}, {@code {1}}… replaced by {@code args}. */
    static String get(String key, Object... args) {
        return Language.text(ResourceBundle.getBundle(BUNDLE, Language.current()), key, args);
    }
}
