package io.powerj.fx;

import java.util.ResourceBundle;

import io.powerj.api.Language;

/** Messages of the {@code io.powerj.fx} module, in the current {@link Language} (FR-61). */
public final class Messages {

    private static final String BUNDLE = "io.powerj.fx.messages";

    private Messages() {
    }

    /** Text of {@code key} with {@code {0}}, {@code {1}}… replaced by {@code args}. */
    public static String get(String key, Object... args) {
        return Language.text(ResourceBundle.getBundle(BUNDLE, Language.current()), key, args);
    }
}
