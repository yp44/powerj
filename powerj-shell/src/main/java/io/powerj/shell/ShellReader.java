package io.powerj.shell;

import java.util.Objects;
import java.util.stream.Stream;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

/** Builds the JLine line reader configured for PowerJ (specification §3.2 and §3.3). */
final class ShellReader {

    static final String CONTINUATION_PROMPT = ">> ";

    private ShellReader() {
    }

    static LineReader create(Terminal terminal, PowerJHome home, ShellConfig config) {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("PowerJ")
                .parser(new ShellParser())
                .expander(new HistoryExpander())
                .variable(LineReader.HISTORY_FILE, home.historyFile())
                .variable(LineReader.HISTORY_SIZE, config.historySize())
                .variable(LineReader.HISTORY_FILE_SIZE, config.historySize())
                .variable(LineReader.SECONDARY_PROMPT_PATTERN, CONTINUATION_PROMPT)
                .option(LineReader.Option.HISTORY_INCREMENTAL, true)   // written after each command (FR-09)
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)   // consecutive duplicates (FR-10)
                .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)  // line starting with a space
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, false)
                .build();
        bindPrefixHistorySearch(reader, terminal);
        return reader;
    }

    /**
     * ↑/↓ browse the history, keeping only the entries that start with the text already typed
     * (FR-06); on multi-line input, they first move between lines.
     */
    private static void bindPrefixHistorySearch(LineReader reader, Terminal terminal) {
        KeyMap<org.jline.reader.Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        var up = new Reference(LineReader.UP_LINE_OR_SEARCH);
        var down = new Reference(LineReader.DOWN_LINE_OR_SEARCH);
        Stream.of("\033[A", "\033OA", KeyMap.key(terminal, Capability.key_up))
                .filter(Objects::nonNull).filter(s -> !s.isEmpty())
                .forEach(seq -> main.bind(up, seq));
        Stream.of("\033[B", "\033OB", KeyMap.key(terminal, Capability.key_down))
                .filter(Objects::nonNull).filter(s -> !s.isEmpty())
                .forEach(seq -> main.bind(down, seq));
    }
}
