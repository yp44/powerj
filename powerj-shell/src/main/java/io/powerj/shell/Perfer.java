package io.powerj.shell;

import java.util.function.Supplier;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;

import io.powerj.core.runtime.Interpreter;

/**
 * Perf mode (FR-76): Alt+P switches between the normal mode and the perf mode, shown by the {@code PJ%} prompt. In perf
 * mode the interpreter measures each line and prints a summary after it. The perf and preview modes exclude each other
 * ({@link ModeController}).
 */
final class Perfer {

    /** Alt+P: ESC then {@code p}, as terminals send Alt+letter. JLine binds it to a history search by default. */
    static final String TOGGLE_KEY = "\033p";
    static final String TOGGLE_WIDGET = "powerj-perf-toggle";

    private final ModeController modes;

    Perfer(Interpreter interpreter, ModeController modes) {
        this.modes = modes;
        modes.register(ShellMode.PERF, () -> interpreter.perfMode(true), () -> interpreter.perfMode(false));
        // The mode may end by itself after a line: the shell follows.
        interpreter.onPerfAutoExit(() -> {
            if (modes.is(ShellMode.PERF)) {
                modes.switchTo(ShellMode.NORMAL);
            }
        });
    }

    boolean on() {
        return modes.is(ShellMode.PERF);
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

    /** Switches the mode; the line typed is kept. The preview mode, if it was on, is left. */
    void toggle() {
        modes.toggle(ShellMode.PERF);
    }
}
