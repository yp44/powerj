package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.core.runtime.PreviewResult;

/** Lines shown below the input in preview mode (FR-66): banner, results, kept lines. */
class PreviewRenderTest {

    private final Previewer previewer = new Previewer(null, null, null, () -> 60, false, new ModeController());

    private String render(PreviewResult result) {
        var rendered = previewer.render(result);
        return rendered == null ? null : rendered.toString();
    }

    @Test
    void nothingForAnEmptyLine() {
        assertThat(render(new PreviewResult.Empty())).isNull();
    }

    @Test
    void bannerWithTheCommonTypeTheCountAndTheErrors() {
        assertThat(render(new PreviewResult.Values(List.of(1, 2), List.of("1", "2"), true, 0)))
                .startsWith("── Integer · 2+ ─").contains("\n1\n2");
        assertThat(render(new PreviewResult.Values(List.of(1, "a"), List.of("1", "a"), false, 2)))
                .startsWith("── Object · 2 · 2 error(s) skipped ─");
        assertThat(render(new PreviewResult.Values(Arrays.asList((Object) null), List.of(), false, 0)))
                .startsWith("── null · 1 ─");
        assertThat(render(new PreviewResult.Values(List.of(), List.of(), false, 0))).startsWith("── no result ─");
        Object anonymous = new Object() { };
        assertThat(render(new PreviewResult.Values(List.of(anonymous), List.of("x"), false, 0)))
                .startsWith("── " + anonymous.getClass().getName() + " · 1");
    }

    @Test
    void theLastResultsStayWhileThePreviewIsOffOrIncomplete() {
        render(new PreviewResult.Values(List.of("kept"), List.of("kept"), false, 0));
        assertThat(render(new PreviewResult.Off("redirection"))).startsWith("── ⏸ preview off: redirection ─")
                .endsWith("\nkept");
        assertThat(render(new PreviewResult.Incomplete("missing \")\""))).startsWith("── … missing \")\" ─")
                .endsWith("\nkept");
    }

    @Test
    void aFailureReplacesTheResults() {
        render(new PreviewResult.Values(List.of("old"), List.of("old"), false, 0));
        assertThat(render(new PreviewResult.Failed("division by zero"))).isEqualTo("division by zero");
        assertThat(render(new PreviewResult.Off("x"))).doesNotContain("old");
    }

    @Test
    void aPreviewCutOffBeforeAnyValueSaysSoInsteadOfNoResult() {
        assertThat(render(new PreviewResult.TimedOut(0))).startsWith("── timed out after 400 ms ─")
                .doesNotContain("no result").doesNotContain("\n");
        assertThat(render(new PreviewResult.TimedOut(2))).startsWith("── timed out after 400 ms · 2 error(s) skipped ─");
    }

    @Test
    void aTimeoutDoesNotKeepTheLinesOfAnotherText() {
        render(new PreviewResult.Values(List.of("old"), List.of("old"), false, 0));
        assertThat(render(new PreviewResult.TimedOut(0))).doesNotContain("old");
        assertThat(render(new PreviewResult.Off("redirection"))).doesNotContain("old");
    }

    @Test
    void aLineThatReallyHasNoResultStillSaysNoResult() {
        assertThat(render(new PreviewResult.Values(List.of(), List.of(), false, 0, false))).startsWith("── no result ─")
                .doesNotContain("timed out");
    }

    @Test
    void aPartialPreviewSaysWhatTheStagesWereCutAt() {
        assertThat(render(new PreviewResult.Values(List.of(1, 2), List.of("1", "2"), false, 0, true)))
                .startsWith("── Integer · 2 · partial · 50 objects max per stage ─").contains("\n1\n2");
        var wide = new Previewer(null, null, null, () -> 200, false, new ModeController());
        assertThat(wide.render(new PreviewResult.Values(List.of(1), List.of("1"), true, 3, true)).toString())
                .startsWith("── Integer · 1+ · partial · 50 objects max per stage · 3 error(s) skipped ─");
        assertThat(render(new PreviewResult.Values(List.of(1), List.of("1"), false, 0, false)))
                .doesNotContain("partial");
    }
}
