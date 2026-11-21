package com.example.samples;

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
import io.powerj.api.Previewable;

/**
 * The translations of every cmdlet of the module: {@code <cmdlet>.summary} and {@code <cmdlet>.option.<long name>}
 * (specification §4.2). The keys are computed from the annotations as the shell computes them, so a key written with the
 * name of the component ({@code skipNull}) instead of the long name ({@code skip-null}) fails here: the shell would not
 * find it and would show the English text of the annotation in French.
 */
class MessagesTest {

    /**
     * The long name the shell gives an option ({@code OptionBinder}): {@code longName}, or the component name in kebab
     * case, also for a component without {@code @Option}.
     */
    private static String longName(RecordComponent component) {
        Option option = component.getAnnotation(Option.class);
        return option != null && !option.longName().isEmpty()
                ? option.longName()
                : component.getName().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    /**
     * The parameter record of a cmdlet: the first type argument of {@code Cmdlet<P, I, O>}, found by its raw type among
     * the interfaces of the class and of its superclasses ({@code implements Previewable, Cmdlet<…>} or a base class).
     */
    static Class<?> parameters(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Type implemented : c.getGenericInterfaces()) {
                if (implemented instanceof ParameterizedType p && p.getRawType() == Cmdlet.class) {
                    return (Class<?>) p.getActualTypeArguments()[0];
                }
            }
        }
        throw new IllegalArgumentException(type + " does not implement Cmdlet<P, I, O> with its type arguments");
    }

    private static List<String> expectedKeys() {
        List<String> keys = new ArrayList<>(List.of("category.Examples", "echo-options.block.failed"));
        for (Cmdlet<?, ?, ?> cmdlet : new SamplesProvider().cmdlets()) {
            String name = cmdlet.getClass().getAnnotation(CmdletInfo.class).name();
            keys.add(name + ".summary");
            for (RecordComponent component : parameters(cmdlet.getClass()).getRecordComponents()) {
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

    /** Test cases for {@link #parameters} and {@link #longName}: shapes that no cmdlet of the module has today. */
    record Bare(int noAnnotation, @Option(longName = "named") String renamed, @Option String plainName) {
    }

    abstract static class Base implements Previewable<Bare>, Cmdlet<Bare, Void, String> {
    }

    static final class Derived extends Base {
        @Override
        public boolean canPreview(Bare params) {
            return true;
        }
    }

    @Test
    void theKeysAreComputedForEveryShapeOfCmdletAndComponent() {
        assertThat(parameters(Derived.class)).isEqualTo(Bare.class);
        assertThat(java.util.Arrays.stream(Bare.class.getRecordComponents()).map(MessagesTest::longName))
                .containsExactly("no-annotation", "named", "plain-name");
    }

    @Test
    void theErrorOfABlockIsFilledWithItsCause() {
        // Surefire runs the tests in English (powerj.language=en)
        assertThat(Messages.get("echo-options.block.failed", "/ by zero")).isEqualTo("--block: / by zero");
    }
}
