package io.powerj.api;

import java.util.ResourceBundle;

/** Messages of the API module, in the current {@link Language}. */
final class Messages {

    private static final String BUNDLE = "io.powerj.api.messages";

    private Messages() {
    }

    static String get(String key, Object... args) {
        return Language.text(ResourceBundle.getBundle(BUNDLE, Language.current()), key, args);
    }
}
