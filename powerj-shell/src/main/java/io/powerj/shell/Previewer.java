package io.powerj.shell;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import io.powerj.core.assist.Completions;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.PreviewResult;
import io.powerj.core.runtime.PreviewSettings;

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
    private static final long STACK_SIZE = 16L * 1024 * 1024;

    private static final AttributedStyle BANNER = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.BLACK);
    private static final AttributedStyle FAILURE = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);

    private final PreviewLineReader reader;
    private final Interpreter interpreter;
    private final Completions completions;
    private final IntSupplier width;
    private final boolean colors;

    private final ModeController modes;

    /** The state shared with the worker thread: what was last evaluated and the lines kept. */
    private final Object lock = new Object();
    private final LatestWinsRunner<String, PreviewResult> runner;

    /** The settings in force when the last evaluation started: what its banner says it was cut at. */
    private volatile PreviewSettings evaluatedWith = PreviewSettings.defaults();

    /**
     * The keys that change the settings, what is shown now (they act while it is values or a timeout), and the menu
     * that shows the settings under the banner of values: opened by the first key, it stays open for the same text.
     */
    private final PreviewControl control = new PreviewControl();
    private volatile PreviewResult resultShown;
    private volatile boolean menuOpen;
    private String menuText;

    /** Text last evaluated (to ignore redisplays that change nothing) and result lines kept when the preview stops. */
    private String lastText;
    private List<AttributedString> lastLines = List.of();

    Previewer(PreviewLineReader reader, Interpreter interpreter, Completions completions, IntSupplier width,
              boolean colors, ModeController modes) {
        this.reader = reader;
        this.interpreter = interpreter;
        this.completions = completions;
        this.width = width;
        this.colors = colors;
        this.modes = modes;
        this.runner = new LatestWinsRunner<>(
                text -> {
                    evaluatedWith = interpreter.previewSettings();
                    return interpreter.preview(text, LIMIT, columns(), colors);
                },
                failure -> new PreviewResult.Failed(String.valueOf(failure)),
                this::show, Duration.ofMillis(DEBOUNCE_MILLIS),
                () -> Duration.ofMillis(interpreter.previewSettings().deadlineMillis()), "powerj-preview",
                STACK_SIZE);
        modes.register(ShellMode.PREVIEW, () -> completions.previewMode(true), this::leave);
    }

    boolean on() {
        return modes.is(ShellMode.PREVIEW);
    }

    /** Binds Alt+V; {@code prompt} gives the prompt to show after the switch. */
    void bind(Supplier<String> prompt) {
        reader.getWidgets().put(TOGGLE_WIDGET, () -> {
            toggle();
            reader.setPrompt(prompt.get());
            return true;
        });
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference(TOGGLE_WIDGET), TOGGLE_KEY);
        for (PreviewControl.Key key : PreviewControl.Key.values()) {
            bindControlKey(main, key);
        }
        reader.onLine(this::changed);
    }

    /**
     * Binds an Alt+arrow of the control. It acts while values or a timeout are shown; otherwise the key does what it did
     * before, so that the editor keeps whatever it had on that key.
     */
    private void bindControlKey(KeyMap<Binding> main, PreviewControl.Key key) {
        Binding before = main.getBound(key.sequence());
        String name = "powerj-preview-control-" + key.name().toLowerCase(java.util.Locale.ROOT);
        reader.getWidgets().put(name, () -> controlKey(key, before));
        main.bind(new Reference(name), key.sequence());
    }

    private boolean controlKey(PreviewControl.Key key, Binding before) {
        PreviewResult shown = resultShown;
        boolean timedOut = shown instanceof PreviewResult.TimedOut;
        if (!on() || !(timedOut || shown instanceof PreviewResult.Values)) {
            switch (before) {
                case Reference reference -> reader.callWidget(reference.name());
                case Widget widget -> widget.apply();
                case null, default -> { }
            }
            return true;
        }
        boolean shows = timedOut || menuOpen;
        openMenu(); // using the control keeps the menu for the values that follow
        if (shows) {
            PreviewSettings settings = interpreter.previewSettings();
            PreviewSettings changed = control.press(key, settings);
            if (!changed.equals(settings)) {
                interpreter.previewSettings(changed);
                synchronized (lock) {
                    lastText = null; // the same text is evaluated again, with the new settings
                }
                changed(reader.getBuffer().toString(), reader.getBuffer().cursor());
            }
        } // else the key only shows the menu: it changes nothing
        reader.preview(render(shown)); // the chosen setting and its value, at once; the evaluation replaces the banner
        return true;
    }

    private void openMenu() {
        synchronized (lock) {
            menuOpen = true;
            menuText = lastText;
        }
    }

    /** Switches the mode; the line typed is kept. */
    void toggle() {
        modes.toggle(ShellMode.PREVIEW);
    }

    /** The mode is left: the completion offers everything again and nothing of the preview stays below the line. */
    private void leave() {
        completions.previewMode(false);
        request(null);
        clear();
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
        resultShown = null;
        menuOpen = false;
        reader.preview(null);
    }

    /** The line or the cursor moved: evaluates the stage under the cursor if it changed. */
    void changed(String buffer, int cursor) {
        if (!on()) {
            return;
        }
        String text = PreviewCut.upToStage(buffer, cursor);
        synchronized (lock) {
            if (menuOpen && !text.equals(menuText)) {
                menuOpen = false; // another line, or another stage: the menu was for the one before
            }
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
        if (text == null) {
            runner.cancel();
        } else {
            runner.submit(text);
        }
    }

    /** Shows the result of an evaluation, if it is still the one of the line and the mode is still on. */
    private void show(PreviewResult result, LatestWinsRunner.Delivery delivery) {
        AttributedString shown = render(result); // fails if the terminal is closed: the result is dropped
        boolean[] displayed = {false};
        delivery.ifLatest(() -> {
            if (on()) {
                reader.preview(shown);
                resultShown = result;
                displayed[0] = true;
            }
        });
        if (displayed[0]) {
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
            case PreviewResult.Values(var values, var formatted, var more, var errors, var partial) -> {
                List<AttributedString> shown = formatted.stream().limit(MAX_LINES)
                        .map(line -> AttributedString.fromAnsi(line).columnSubSequence(0, columns)).toList();
                synchronized (lock) {
                    lastLines = shown;
                }
                String count = values.isEmpty() ? Messages.get("preview.banner.empty")
                        : Messages.get("preview.banner.count", typeName(values), values.size() + (more ? "+" : ""));
                if (partial) {
                    count += " · " + Messages.get("preview.banner.partial", evaluatedWith.stageLimit());
                }
                if (errors > 0) {
                    count += " · " + Messages.get("preview.banner.errors", errors);
                }
                lines.add(banner(count, columns, BANNER));
                if (menuOpen) {
                    lines.add(menuLine(columns));
                }
                lines.addAll(shown);
            }
            case PreviewResult.TimedOut(var errors) -> {
                synchronized (lock) {
                    lastLines = List.of(); // the lines of another text would say the opposite of the banner
                }
                return timedOutBanner(errors, columns);
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

    /**
     * {@code ── timed out after 400 ms · [duration 400 ms]  max/stage 50 · Alt+◄► choose, Alt+▲▼ change ───}: what happened,
     * the two settings that can be changed (the chosen one between brackets) and the keys. On a narrow terminal the
     * brief form: what has to be cut is the help, not the settings.
     */
    private AttributedString timedOutBanner(int errors, int columns) {
        PreviewSettings settings = interpreter.previewSettings();
        String skipped = errors > 0 ? " · " + Messages.get("preview.banner.errors", errors) : "";
        AttributedString full = bannerOf(Messages.get("preview.banner.timedOut", evaluatedWith.deadlineMillis()) + skipped,
                control.fields(settings, false), PreviewControl.help(false), columns);
        if (full.columnLength() <= columns) {
            return full;
        }
        return bannerOf(Messages.get("preview.banner.timedOutBrief") + skipped, control.fields(settings, true),
                PreviewControl.help(true), columns).columnSubSequence(0, columns);
    }

    /** {@code [duration 400 ms]  max/stage 50 · Alt+◄► choose, Alt+▲▼ change}: the menu under the banner of values. */
    private AttributedString menuLine(int columns) {
        PreviewSettings settings = interpreter.previewSettings();
        AttributedString full = menuOf(control.fields(settings, false), PreviewControl.help(false));
        if (full.columnLength() <= columns) {
            return full;
        }
        AttributedString brief = menuOf(control.fields(settings, true), PreviewControl.help(true));
        return brief.columnSubSequence(0, Math.min(columns, brief.columnLength()));
    }

    private static AttributedString menuOf(AttributedString fields, String help) {
        return new AttributedStringBuilder().append(fields).style(BANNER).append(" · ").append(help)
                .toAttributedString();
    }

    private static AttributedString bannerOf(String what, AttributedString fields, String help, int columns) {
        var builder = new AttributedStringBuilder();
        builder.style(BANNER).append("── ").append(what).append(" · ").append(fields).style(BANNER).append(" · ")
                .append(help).append(" ");
        while (builder.columnLength() < columns) {
            builder.append('─');
        }
        return builder.toAttributedString();
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
