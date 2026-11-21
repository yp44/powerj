package com.example.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/**
 * The translations of every cmdlet of the module: {@code <cmdlet>.summary} and {@code <cmdlet>.option.<long name>}
 * (specification §4.2). The keys are computed from the annotations as the shell computes them, so a key written with the
 * name of the component ({@code skipNull}) instead of the long name ({@code skip-null}) fails here: the shell would not
 * find it and would show the English text of the annotation in French.
 */
class MessagesTest {

    /** The long name the shell gives an option: {@code longName}, or the component name in kebab case. */
    private static String longName(java.lang.reflect.RecordComponent component) {
        String declared = component.getAnnotation(Option.class).longName();
        return declared.isEmpty()
                ? component.getName().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT)
                : declared;
    }

    private static List<String> expectedKeys() {
        List<String> keys = new ArrayList<>(List.of("category.Examples", "echo-options.block.failed"));
        for (Cmdlet<?, ?, ?> cmdlet : new SamplesProvider().cmdlets()) {
            String name = cmdlet.getClass().getAnnotation(CmdletInfo.class).name();
            keys.add(name + ".summary");
            Class<?> params = (Class<?>) ((java.lang.reflect.ParameterizedType) cmdlet.getClass()
                    .getGenericInterfaces()[0]).getActualTypeArguments()[0];
            for (var component : params.getRecordComponents()) {
                keys.add(name + ".option." + longName(component));
            }
        }
        return keys;
    }

    @Test
    void everyTextOfEveryCmdletIsTranslatedInEnglishAndFrench() {
        List<String> keys = expectedKeys();
        assertThat(keys).contains("passthrough.option.skip-null", "echo-options.option.tag");
        ResourceBundle en = new SamplesProvider().messages(Locale.ENGLISH);
        ResourceBundle fr = new SamplesProvider().messages(Locale.FRENCH);
        assertThat(en.keySet()).containsAll(keys);
        assertThat(fr.keySet()).containsAll(keys);
        assertThat(en.keySet()).containsExactlyInAnyOrderElementsOf(fr.keySet());
    }

    @Test
    void theEnglishBundleRepeatsTheTextsOfTheAnnotations() {
        ResourceBundle en = new SamplesProvider().messages(Locale.ENGLISH);
        for (Cmdlet<?, ?, ?> cmdlet : new SamplesProvider().cmdlets()) {
            CmdletInfo info = cmdlet.getClass().getAnnotation(CmdletInfo.class);
            assertThat(en.getString(info.name() + ".summary")).isEqualTo(info.summary());
        }
        for (var component : EchoOptionsParams.class.getRecordComponents()) {
            assertThat(en.getString("echo-options.option." + longName(component)))
                    .as(component.getName()).isEqualTo(component.getAnnotation(Option.class).description());
        }
        assertThat(new SamplesProvider().messages(Locale.FRENCH).getString("category.Examples")).isEqualTo("Exemples");
    }

    @Test
    void theErrorOfABlockIsFilledWithItsCause() {
        // Surefire runs the tests in English (powerj.language=en)
        assertThat(Messages.get("echo-options.block.failed", "/ by zero")).isEqualTo("--block: / by zero");
    }
}
