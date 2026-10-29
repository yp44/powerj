package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.PreviewResult;
import io.powerj.core.runtime.PreviewSettings;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/** Lines shown below the input in preview mode (FR-66): banner, results, kept lines. */
class PreviewRenderTest {

    private final Interpreter interpreter = interpreter();
    private final Previewer previewer = new Previewer(null, interpreter, null, () -> 60, false, new ModeController());

    private static Interpreter interpreter() {
        Path here = Path.of(".").toAbsolutePath();
        return new Interpreter(new Session(here, here, Map.of()),
                new ShellIo(new PrintWriter(new StringWriter(), true), _ -> { }, false), Map.of(),
                CmdletRegistry.discover());
    }

    private String renderWide(PreviewResult result) {
        return new Previewer(null, interpreter, null, () -> 200, false, new ModeController()).render(result).toString();
    }

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
        assertThat(renderWide(new PreviewResult.TimedOut(0))).startsWith("── timed out after 400 ms · ").doesNotContain("no result")
                .doesNotContain("\n");
        assertThat(renderWide(new PreviewResult.TimedOut(2)))
                .startsWith("── timed out after 400 ms · 2 error(s) skipped · ");
    }

    @Test
    void theBannerOfATimeoutOffersTheTwoSettingsAndTheKeys() {
        assertThat(renderWide(new PreviewResult.TimedOut(0))).startsWith(
                "── timed out after 400 ms · [duration 400 ms]  max/stage 50 · Alt+◄► choose, Alt+▲▼ change ─");
    }

    @Test
    void theBannerOfATimeoutShowsTheSettingsOfTheMoment() {
        interpreter.previewSettings(new PreviewSettings(1600, 20));

        assertThat(renderWide(new PreviewResult.TimedOut(0))).contains("[duration 1600 ms]  max/stage 20");
    }

    @Test
    void aSettingThatCannotGoUpAnyMoreSaysSo() {
        interpreter.previewSettings(new PreviewSettings(3200, 500));

        assertThat(renderWide(new PreviewResult.TimedOut(0))).contains("[duration 3200 ms (max)]  max/stage 500 (max)");
    }

    @Test
    void onANarrowTerminalTheBriefFormKeepsTheSettingsAndCutsTheHelp() {
        assertThat(render(new PreviewResult.TimedOut(0))).startsWith("── timed out · [400 ms]  50/stage · Alt+◄►▲▼ ─");
        assertThat(previewerOfWidth(30).render(new PreviewResult.TimedOut(0)).toString())
                .isEqualTo("── timed out · [400 ms]  50/st");
    }

    private Previewer previewerOfWidth(int columns) {
        return new Previewer(null, interpreter, null, () -> columns + 1, false, new ModeController());
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
