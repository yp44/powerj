package io.powerj.shell;

import java.util.function.Supplier;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;

import io.powerj.core.exec.Interpreter;

/**
 * Perf mode (FR-76): Alt+P switches between the normal mode and the perf mode, shown by the {@code PJ%} prompt. In perf
 * mode the interpreter measures each line and prints a summary after it. The perf and preview modes exclude each other,
 * as the modes of vi do.
 */
final class Perfer {

    /** Alt+P: ESC then {@code p}, as terminals send Alt+letter. JLine binds it to a history search by default. */
    static final String TOGGLE_KEY = "\033p";
    static final String TOGGLE_WIDGET = "powerj-perf-toggle";

    private final Interpreter interpreter;
    /** Preview mode, or {@code null} if the line reader cannot display it. */
    private final Previewer previewer;

    Perfer(Interpreter interpreter, Previewer previewer) {
        this.interpreter = interpreter;
        this.previewer = previewer;
    }

    boolean on() {
        return interpreter.perfMode();
    }

    /** Binds Alt+P; {@code prompt} gives the prompt to show after the switch. */
    void bind(LineReaderImpl reader, Supplier<String> prompt) {
        reader.getWidgets().put(TOGGLE_WIDGET, () -> {
            toggle();
            reader.setPrompt(prompt.get().replace("%", "%%")); // JLine reads "%" as a pattern here
            return true;
        });
        KeyMap<org.jline.reader.Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference(TOGGLE_WIDGET), TOGGLE_KEY);
    }

    /** Switches the mode; the line typed is kept. */
    void toggle() {
        boolean on = !on();
        if (on && previewer != null && previewer.on()) {
            previewer.toggle();
        }
        interpreter.perfMode(on);
    }

    /** Leaves the perf mode, because the preview mode is entered. */
    void off() {
        interpreter.perfMode(false);
    }
}
