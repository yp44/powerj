package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/**
 * The help of a built-in cmdlet is written once (audit v2, C-03; FR-61): {@code help} shows the text of the bundle
 * ({@code <name>.summary}, {@code <name>.option.<long>}), and the annotation, which a reader of the class sees and which
 * is the fallback when the bundle has no key, must say the same as the English bundle. {@code ps --names} said
 * "case ignored" in its annotation and "case ignored on Windows only" in the bundle.
 */
class HelpTextsTest {

    private static final ResourceBundle ENGLISH = new BuiltinCmdlets().messages(Locale.ENGLISH);

    /** {@code key: annotation ≠ bundle} for every text that differs or has no key. */
    static List<String> differences() {
        List<String> found = new ArrayList<>();
        for (Cmdlet<?, ?, ?> cmdlet : new BuiltinCmdlets().cmdlets()) {
            CmdletInfo info = cmdlet.getClass().getAnnotation(CmdletInfo.class);
            compare(info.name() + ".summary", info.summary(), found);
            for (RecordComponent component : params(cmdlet).getRecordComponents()) {
                Option option = component.getAnnotation(Option.class);
                if (option != null) {
                    String key = info.name() + ".option." + CmdletOptionLettersTest.longName(component, option);
                    compare(key, option.description(), found);
                }
            }
        }
        return found;
    }

    private static void compare(String key, String annotation, List<String> found) {
        if (!ENGLISH.containsKey(key)) {
            found.add(key + ": no key in messages_en.properties");
        } else if (!ENGLISH.getString(key).equals(annotation)) {
            found.add(key + ": annotation \"" + annotation + "\" ≠ bundle \"" + ENGLISH.getString(key) + "\"");
        }
    }

    private static Class<?> params(Cmdlet<?, ?, ?> cmdlet) {
        for (Type type : cmdlet.getClass().getGenericInterfaces()) {
            if (type instanceof ParameterizedType p && p.getRawType() == Cmdlet.class) {
                return (Class<?>) p.getActualTypeArguments()[0];
            }
        }
        throw new AssertionError("no Cmdlet<Params, …> on " + cmdlet.getClass());
    }

    @Test
    void theAnnotationSaysWhatTheEnglishBundleSays() {
        assertThat(differences()).isEmpty();
    }
}
