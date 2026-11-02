package io.powerj.text;

import java.util.ResourceBundle;

import io.powerj.api.Language;

/**
 * The texts of this module, in the current {@link Language} (FR-61). Each module reads its own bundle from itself: the
 * bundle of another module is not visible from here (the seven {@code Messages} classes of the product stay for that
 * reason, see the CHANGELOG).
 */
final class Messages {

    private static final String BUNDLE = "io.powerj.text.messages";

    private Messages() {
    }

    /** Does the bundle define {@code key}? (the other methods answer the key itself when it is missing) */
    static boolean has(String key) {
        return ResourceBundle.getBundle(BUNDLE, Language.current()).containsKey(key);
    }

    /** Text of {@code key} with {@code {0}}, {@code {1}}… replaced by {@code args}. */
    static String get(String key, Object... args) {
        return Language.text(ResourceBundle.getBundle(BUNDLE, Language.current()), key, args);
    }
}
