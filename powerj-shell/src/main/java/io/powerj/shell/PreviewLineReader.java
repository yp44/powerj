package io.powerj.shell;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.jline.reader.MaskingCallback;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

/**
 * JLine line reader that shows the preview (FR-66) below the line being typed, in the area where JLine lists the
 * completion candidates. The candidates take precedence while they are displayed; the preview never stays on screen
 * once the line is accepted or abandoned.
 */
final class PreviewLineReader extends org.jline.reader.impl.LineReaderImpl {

    private volatile AttributedString preview;
    /** Told of the line and the cursor at each redisplay: after each key, cursor moves included. */
    private final java.util.List<BiConsumer<String, Integer>> lineListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The line is being accepted or abandoned: the preview is cleared from the screen. */
    private volatile boolean leaving;
    private final Supplier<AttributedString> previewPost = () -> {
        AttributedString text = preview;
        return text == null ? AttributedString.EMPTY : text;
    };

    PreviewLineReader(Terminal terminal, String appName, Map<String, Object> variables) {
        super(terminal, appName, variables);
    }

    void onLine(BiConsumer<String, Integer> listener) {
        lineListeners.add(listener);
    }

    /** Text shown below the line, or {@code null} for none; displayed at the next redisplay. */
    void preview(AttributedString text) {
        this.preview = text;
    }

    @Override
    public String readLine(String prompt, String rightPrompt, MaskingCallback maskingCallback, String buffer) {
        leaving = false;
        return super.readLine(prompt, rightPrompt, maskingCallback, buffer);
    }

    @Override
    protected void redisplay(boolean flush) {
        if (!leaving) {
            String line = buf.toString();
            int cursor = buf.cursor();
            lineListeners.forEach(listener -> listener.accept(line, cursor));
        }
        if (post == null || post == previewPost) {
            post = preview == null || leaving ? null : previewPost;
        }
        super.redisplay(flush);
    }

    @Override
    protected boolean acceptLine() {
        leaving = true;
        if (post == previewPost) {
            post = null;
        }
        return super.acceptLine();
    }

    @Override
    protected void cleanup() {
        leaving = true;
        if (post == previewPost) {
            post = null;
        }
        super.cleanup();
    }
}
