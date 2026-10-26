package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.runtime.Session;

/** {@code PJ <dir>> }, with a marker for the preview and the perf modes, in color on a terminal that has colors. */
class PromptRendererTest {

    @TempDir
    Path tmp;

    private String render(ShellMode mode, boolean colors) throws Exception {
        try (var screen = new TestTerminal("")) {
            var modes = new ModeController();
            modes.switchTo(mode);
            var session = new Session(tmp, tmp, Map.of());
            return new PromptRenderer(session, modes, screen.terminal(), colors).render();
        }
    }

    @Test
    void theNormalPromptIsPJAndTheCurrentDirectory() throws Exception {
        assertThat(render(ShellMode.NORMAL, false)).isEqualTo("PJ " + tmp.toAbsolutePath().normalize() + "> ");
    }

    @Test
    void thePreviewAndPerfModesHaveTheirMarker() throws Exception {
        assertThat(render(ShellMode.PREVIEW, false)).startsWith("PJ? ").endsWith("> ");
        assertThat(render(ShellMode.PERF, false)).startsWith("PJ% ").endsWith("> ");
    }

    @Test
    void theMarkerIsInColorOnATerminalThatHasColorsAndTheNormalPromptIsNot() throws Exception {
        assertThat(render(ShellMode.PREVIEW, true)).contains("\033[").contains("PJ?");
        assertThat(render(ShellMode.PERF, true)).contains("\033[").contains("PJ%");
        assertThat(render(ShellMode.NORMAL, true)).doesNotContain("\033[");
    }
}
