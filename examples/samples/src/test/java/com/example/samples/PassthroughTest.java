package com.example.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

import io.powerj.api.CmdletInfo;
import io.powerj.testing.CmdletDriver;
import io.powerj.testing.TestContext;

class PassthroughTest {

    private static final PassthroughParams NONE = new PassthroughParams(false, false);
    private static final PassthroughParams SKIP_NULL = new PassthroughParams(true, false);
    private static final PassthroughParams OPTIONAL = new PassthroughParams(false, true);
    private static final PassthroughParams BOTH = new PassthroughParams(true, true);

    private final TestContext<Object> context = new TestContext<>(Path.of("."));

    {
        context.hasInput = true;
    }

    private List<Object> run(PassthroughParams params, Object... inputs) throws Exception {
        return CmdletDriver.run(new Passthrough(), params, context, inputs);
    }

    @Test
    void withoutOptionsTheObjectsComeOutUnchangedAndInOrderNullIncluded() throws Exception {
        Object same = new Object();
        assertThat(run(NONE, "a", null, 3, same)).containsExactly("a", null, 3, same);
        assertThat(context.emitted.get(3)).isSameAs(same);
    }

    @Test
    void skipNullDropsTheNullObjectsAndOnlyThem() throws Exception {
        assertThat(run(SKIP_NULL, null, "a", null, "", 0, null)).containsExactly("a", "", 0);
    }

    @Test
    void optionalWrapsEachObjectAndANullIsAnEmptyOptional() throws Exception {
        assertThat(run(OPTIONAL, "a", null, 3)).containsExactly(Optional.of("a"), Optional.empty(), Optional.of(3));
    }

    @Test
    void bothOptionsDropTheNullsBeforeWrapping() throws Exception {
        assertThat(run(BOTH, "a", null, "b")).containsExactly(Optional.of("a"), Optional.of("b"));
    }

    @Test
    void noInputGivesNoOutput() throws Exception {
        for (PassthroughParams params : List.of(NONE, SKIP_NULL, OPTIONAL, BOTH)) {
            assertThat(run(params)).isEmpty();
        }
    }

    @Test
    void theCmdletIsDeclaredAsPreviewableButNotAsPassThrough() {
        CmdletInfo info = Passthrough.class.getAnnotation(CmdletInfo.class);
        assertThat(info.name()).isEqualTo("passthrough");
        assertThat(info.preview()).isTrue();
        // --optional changes the type of the output: completion must not look through the cmdlet
        assertThat(info.passThrough()).isFalse();
    }

    @Test
    void theProviderListsPassthrough() {
        assertThat(new SamplesProvider().cmdlets()).singleElement().isInstanceOf(Passthrough.class);
    }

    @Test
    void theTextsAreTranslatedInEnglishAndFrench() {
        List<String> keys = List.of("category.Examples", "passthrough.summary", "passthrough.option.skipNull",
                "passthrough.option.optional");
        ResourceBundle en = new SamplesProvider().messages(Locale.ENGLISH);
        ResourceBundle fr = new SamplesProvider().messages(Locale.FRENCH);
        assertThat(en.keySet()).containsAll(keys);
        assertThat(fr.keySet()).containsAll(keys);
        assertThat(fr.getString("category.Examples")).isEqualTo("Exemples");
        assertThat(en.getString("passthrough.summary"))
                .isEqualTo(Passthrough.class.getAnnotation(CmdletInfo.class).summary());
    }
}
