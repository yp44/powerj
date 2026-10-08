package io.powerj.cmdlets;

import java.util.ResourceBundle;

import io.powerj.api.Language;

/** Messages of the {@code io.powerj.cmdlets} package, in the current {@link Language} (FR-60). */
final class Messages {

    private static final String BUNDLE = "io.powerj.cmdlets.messages";

    private Messages() {
    }

    /** Text of {@code key} with {@code {0}}, {@code {1}}… replaced by {@code args}. */
    static String get(String key, Object... args) {
        return Language.text(ResourceBundle.getBundle(BUNDLE, Language.current()), key, args);
    }
}
