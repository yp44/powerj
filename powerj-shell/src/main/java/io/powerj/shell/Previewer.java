package io.powerj.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import io.powerj.core.exec.Completions;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.PreviewResult;

/**
 * Preview mode (FR-66): Alt+V switches between the normal mode and the preview mode, shown by the {@code PJ?} prompt.
 * In preview mode, each change of the line evaluates it in the background ({@link Interpreter#preview}) and its first
 * results are shown below the line, for the stage where the cursor is.
 */
final class Previewer {

    /** Alt+V ("View"): ESC then {@code v}, as terminals send Alt+letter. */
    static final String TOGGLE_KEY = "\033v";
    static final String TOGGLE_WIDGET = "powerj-preview-toggle";

    /** Values evaluated and lines of results shown at most. */
    static final int LIMIT = 10;
    private static final int MAX_LINES = 12;
    /** Pause after a keystroke before evaluating: typing fast does not start an evaluation per key. */
    private static final long DEBOUNCE_MILLIS = 60;
    /** Evaluation time limit: beyond it, the values already received are shown. */
    private static final long DEADLINE_MILLIS = 400;
    private static final long STACK_SIZE = 16L * 1024 * 1024;

    private static final AttributedStyle BANNER = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.BLACK);
    private static final AttributedStyle FAILURE = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);

    private final PreviewLineReader reader;
    private final Interpreter interpreter;
    private final Completions completions;
    private final IntSupplier width;
    private final boolean colors;

    private volatile boolean on;
    /** Run when the mode is entered: the perf mode is left (FR-76). */
    private Runnable onEnter = () -> { };

    private final Object lock = new Object();
    /** Text to evaluate, and its number: each change of the line gives a new one. */
    private String requested;
    private long requestedGeneration;
    private long doneGeneration;
    /** The worker is inside {@link Interpreter#preview}: interrupting it cancels the evaluation. */
    private boolean evaluating;
    private Thread worker;

    /** Text last evaluated (to ignore redisplays that change nothing) and result lines kept when the preview stops. */
    private String lastText;
    private List<AttributedString> lastLines = List.of();

    Previewer(PreviewLineReader reader, Interpreter interpreter, Completions completions, IntSupplier width,
              boolean colors) {
        this.reader = reader;
        this.interpreter = interpreter;
        this.completions = completions;
        this.width = width;
        this.colors = colors;
    }

    boolean on() {
        return on;
    }

    /** Binds Alt+V; {@code prompt} gives the prompt to show after the switch. */
    void bind(Supplier<String> prompt) {
        reader.getWidgets().put(TOGGLE_WIDGET, () -> {
            toggle();
            reader.setPrompt(prompt.get());
            return true;
        });
        KeyMap<org.jline.reader.Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference(TOGGLE_WIDGET), TOGGLE_KEY);
        reader.onLine(this::changed);
    }

    void onEnter(Runnable action) {
        this.onEnter = action;
    }

    /** Switches the mode; the line typed is kept. */
    void toggle() {
        on = !on;
        if (on) {
            onEnter.run();
        }
        completions.previewMode(on);
        if (!on) {
            request(null);
            clear();
        }
    }

    /** New line: nothing of the previous one stays below it. */
    void reset() {
        request(null);
        clear();
    }

    private void clear() {
        synchronized (lock) {
            lastText = null;
            lastLines = List.of();
        }
        reader.preview(null);
    }

    /** The line or the cursor moved: evaluates the stage under the cursor if it changed. */
    void changed(String buffer, int cursor) {
        if (!on) {
            return;
        }
        String text = PreviewCut.upToStage(buffer, cursor);
        synchronized (lock) {
            if (text.equals(lastText)) {
                return;
            }
            lastText = text;
        }
        if (text.isBlank()) {
            request(null);
            clear();
            return;
        }
        request(text);
    }

    private void request(String text) {
        synchronized (lock) {
            requested = text;
            requestedGeneration++;
            if (evaluating) {
                worker.interrupt(); // the running evaluation is out of date
            }
            if (text != null && worker == null) {
                worker = Thread.ofPlatform().name("powerj-apercu").daemon().stackSize(STACK_SIZE).start(this::work);
            }
            lock.notifyAll();
        }
    }

    /** Evaluates the latest request, after a short pause, then shows its result. */
    private void work() {
        while (true) {
            String text;
            long generation;
            try {
                synchronized (lock) {
                    while (requestedGeneration == doneGeneration) {
                        lock.wait();
                    }
                    generation = requestedGeneration;
                    lock.wait(DEBOUNCE_MILLIS);
                    if (generation != requestedGeneration) {
                        continue; // still typing
                    }
                    text = requested;
                    if (text == null) {
                        doneGeneration = generation;
                        continue;
                    }
                    evaluating = true;
                }
            } catch (InterruptedException _) {
                continue;
            }
            PreviewResult result;
            Thread self = Thread.currentThread();
            Thread deadline = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(DEADLINE_MILLIS);
                    synchronized (lock) {
                        if (evaluating && requestedGeneration == generation) {
                            self.interrupt();
                        }
                    }
                } catch (InterruptedException _) {
                    // evaluation finished first
                }
            });
            try {
                result = interpreter.preview(text, LIMIT, columns(), colors);
            } catch (Throwable t) {
                result = new PreviewResult.Failed(String.valueOf(t));
            } finally {
                synchronized (lock) {
                    evaluating = false;
                }
                deadline.interrupt();
                Thread.interrupted();
            }
            AttributedString shown;
            try {
                shown = render(result);
            } catch (RuntimeException _) {
                continue; // terminal closed
            }
            synchronized (lock) {
                doneGeneration = Math.max(doneGeneration, generation);
                if (generation != requestedGeneration || !on) {
                    continue; // the line has changed meanwhile, or was accepted
                }
                reader.preview(shown);
            }
            redisplay();
        }
    }

    private void redisplay() {
        try {
            if (reader.isReading()) {
                reader.callWidget(LineReader.REDISPLAY);
            }
        } catch (RuntimeException _) {
            // the line was accepted meanwhile, or the terminal is closed: nothing to show
        }
    }

    /** Width of the preview lines: the terminal width, less one column so that no line wraps. */
    private int columns() {
        return Math.max(20, width.getAsInt() - 1);
    }

    /** Lines shown below the input: a banner, then the results. */
    AttributedString render(PreviewResult result) {
        int columns = columns();
        List<AttributedString> lines = new ArrayList<>();
        switch (result) {
            case PreviewResult.Empty() -> {
                synchronized (lock) {
                    lastLines = List.of();
                }
                return null;
            }
            case PreviewResult.Values(var values, var formatted, var more, var errors) -> {
                List<AttributedString> shown = formatted.stream().limit(MAX_LINES)
                        .map(line -> AttributedString.fromAnsi(line).columnSubSequence(0, columns)).toList();
                synchronized (lock) {
                    lastLines = shown;
                }
                String count = values.isEmpty() ? Messages.get("preview.banner.empty")
                        : Messages.get("preview.banner.count", typeName(values), values.size() + (more ? "+" : ""));
                if (errors > 0) {
                    count += " · " + Messages.get("preview.banner.errors", errors);
                }
                lines.add(banner(count, columns, BANNER));
                lines.addAll(shown);
            }
            case PreviewResult.Off(var reason) -> {
                lines.add(banner(Messages.get("preview.banner.off", reason), columns, BANNER));
                lines.addAll(kept());
            }
            case PreviewResult.Incomplete(var message) -> {
                lines.add(banner(Messages.get("preview.banner.incomplete", message), columns, BANNER));
                lines.addAll(kept());
            }
            case PreviewResult.Failed(var message) -> {
                synchronized (lock) {
                    lastLines = List.of();
                }
                lines.add(new AttributedString(message, FAILURE).columnSubSequence(0, columns));
            }
        }
        return AttributedString.join(new AttributedString("\n"), lines);
    }

    private List<AttributedString> kept() {
        synchronized (lock) {
            return lastLines;
        }
    }

    /** {@code ── FileEntry · 10+ ─────}: dimmed line that separates the input from the results. */
    private static AttributedString banner(String text, int columns, AttributedStyle style) {
        var builder = new AttributedStringBuilder();
        builder.style(style).append("── ").append(text).append(" ");
        while (builder.columnLength() < columns) {
            builder.append('─');
        }
        return builder.toAttributedString().columnSubSequence(0, columns);
    }

    /** Simple name of the type common to all the values, otherwise {@code Object}. */
    private static String typeName(List<Object> values) {
        Class<?> type = null;
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (type == null) {
                type = value.getClass();
            } else if (type != value.getClass()) {
                return "Object";
            }
        }
        if (type == null) {
            return "null";
        }
        return type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName();
    }
}
