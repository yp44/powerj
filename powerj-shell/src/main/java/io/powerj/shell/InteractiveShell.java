package io.powerj.shell;

import java.util.Map;

import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

import io.powerj.core.assist.Completions;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.perf.PerfMode;
import io.powerj.core.perf.Profiler;
import io.powerj.core.runtime.ConsoleInput;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;
import io.powerj.core.runtime.Supervisor;

/**
 * Assembles the interactive shell on a line reader: the interpreter with its output and its {@code history} command, the
 * assistance of the line editor (completion, highlighting, the parser that tells when a line is complete), the preview
 * and perf modes with their keys, the prompt, and the {@link Repl} that reads and executes. It is the one place that
 * knows how these fit; the {@code Repl} used to do it in its constructor, and to guess from the type of the reader which
 * features to leave out.
 */
final class InteractiveShell {

    private InteractiveShell() {
    }

    static Repl create(PreviewLineReader reader, Supervisor supervisor, Session session, CmdletRegistry registry) {
        Terminal terminal = reader.getTerminal();
        var out = terminal.writer();
        boolean interactive = !terminal.getType().startsWith("dumb");
        boolean colors = interactive && colors(terminal, session);
        var errors = new ErrorPrinter(terminal, out);
        var io = new ShellIo(out, errors::print, interactive, terminal::getColumns, consoleInput(terminal),
                colors);
        var interpreter = new Interpreter(session, io, Map.of("history", new HistoryCommand(reader.getHistory())::run),
                registry, new PerfMode(session, io, Profiler.discover()));
        // Completion (Tab) and input highlighting (FR-08, FR-21 to FR-26).
        var completions = new Completions(interpreter);
        completions.warmUp();
        reader.setParser(new ShellParser(completions));
        reader.setCompleter(ShellCompletion.completer());
        reader.setHighlighter(ShellCompletion.highlighter(interpreter));
        var modes = new ModeController();
        var prompt = new PromptRenderer(session, modes, terminal, colors);
        Runnable afterInput = () -> { };
        if (interactive) {
            var previewer = new Previewer(reader, interpreter, completions, terminal::getColumns, colors, modes);
            previewer.bind(prompt::render);
            new Perfer(interpreter, modes).bind(reader, prompt::render);
            afterInput = previewer::reset; // stops the evaluation of the line being previewed (FR-66)
        }
        return new Repl(reader::readLine, terminal, supervisor, session, interpreter, prompt, errors, afterInput);
    }

    /** {@code true} if the terminal displays ANSI colors (FR-64), unless {@code NO_COLOR} is set. */
    private static boolean colors(Terminal terminal, Session session) {
        Integer colors = terminal.getNumericCapability(InfoCmp.Capability.max_colors);
        String noColor = session.environment().get("NO_COLOR");
        return colors != null && colors >= 8 && (noColor == null || noColor.isEmpty());
    }

    /**
     * Suspends JLine's reading of the console while a native program reads it (vim…): under Windows its
     * pump thread reads the console permanently and would steal keys, and turn Ctrl+C into a cancellation.
     */
    private static ConsoleInput consoleInput(Terminal terminal) {
        if (!terminal.canPauseResume()) {
            return ConsoleInput.NONE;
        }
        return new ConsoleInput() {
            @Override
            public void pause() {
                // Never pause(true): it interrupts the pump thread, and an interrupt landing while the pump
                // hands over an input event makes JLine close the whole terminal. pause() only asks the
                // pump to stop at its next poll (at most 100 ms, during the program's start-up).
                terminal.pause();
            }

            @Override
            public void resume() {
                terminal.resume();
            }
        };
    }
}
