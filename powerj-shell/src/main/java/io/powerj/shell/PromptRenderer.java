package io.powerj.shell;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import io.powerj.core.runtime.Session;

/** The prompt (FR-01): {@code PJ <dir>> }, {@code PJ? <dir>> } in preview mode (FR-66), {@code PJ% <dir>> } in perf mode (FR-76). */
final class PromptRenderer {

    private final Session session;
    private final ModeController modes;
    private final Terminal terminal;
    private final boolean colors;

    PromptRenderer(Session session, ModeController modes, Terminal terminal, boolean colors) {
        this.session = session;
        this.modes = modes;
        this.terminal = terminal;
        this.colors = colors;
    }

    String render() {
        String mode = switch (modes.current()) {
            case NORMAL -> "PJ ";
            case PREVIEW -> marker("PJ?", AttributedStyle.CYAN);
            case PERF -> marker("PJ%", AttributedStyle.YELLOW);
        };
        return mode + session.currentDirectory() + "> ";
    }

    /**
     * The prompt as JLine must receive it: every {@code %} doubled, because JLine reads {@code %N}, {@code %M},
     * {@code %P}, {@code %{}… in a prompt as patterns, and a current directory is any name (U-13). The one place that
     * knows the rule: the first prompt, and the prompt reset when the mode changes, all come from here.
     */
    String forJLine() {
        return render().replace("%", "%%");
    }

    private String marker(String text, int color) {
        return colors ? new AttributedString(text, AttributedStyle.DEFAULT.foreground(color)).toAnsi(terminal) + " "
                : text + " ";
    }
}
