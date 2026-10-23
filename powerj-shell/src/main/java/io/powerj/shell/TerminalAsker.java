package io.powerj.shell;

import java.util.List;
import java.util.Optional;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

/**
 * Asks on the terminal whether a module that uses native code may be loaded. It has its own plain line reader: the
 * answer is not highlighted as a command, not completed and not kept in the history.
 */
final class TerminalAsker implements NativeAccessConsent.Asker {

    private final Terminal terminal;

    TerminalAsker(Terminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public Optional<Boolean> ask(String module, List<String> layer) {
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        try {
            String answer = reader.readLine(Messages.get("nativeAccess.prompt", module, String.join(", ", layer)));
            return Optional.of(NativeAccessConsent.isYes(answer));
        } catch (UserInterruptException | EndOfFileException _) {
            return Optional.empty();
        }
    }
}
