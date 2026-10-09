package io.powerj.shell;

import java.util.ListIterator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.reader.Expander;
import org.jline.reader.History;

/**
 * History expansion at the start of the line only (specification FR-11): {@code !!}, {@code !n}
 * and {@code !text}, optionally followed by the rest of the line. A {@code !} elsewhere in the line
 * remains a negation ({@code where { !$_.dir }}).
 */
final class HistoryExpander implements Expander {

    private static final Pattern EVENT = Pattern.compile("^!(!|\\d+|[^\\s!]+)(.*)", Pattern.DOTALL);

    @Override
    public String expandHistory(History history, String line) {
        Matcher m = EVENT.matcher(line);
        if (!m.matches()) {
            return line;
        }
        String event = m.group(1);
        Optional<String> command = switch (event) {
            case "!" -> history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.last()));
            case String n when n.chars().allMatch(Character::isDigit) -> byNumber(history, n);
            case String prefix -> lastStartingWith(history, prefix);
        };
        // JLine ignores the exception and leaves the line as is: the REPL will report the failure.
        return command.map(c -> c + m.group(2))
                .orElseThrow(() -> new IllegalArgumentException("!" + event + ": event not found"));
    }

    @Override
    public String expandVar(String word) {
        return word;
    }

    /** Entry number {@code n} as displayed by {@code history} (numbering starts at 1). */
    private static Optional<String> byNumber(History history, String n) {
        try {
            int index = Integer.parseInt(n) - 1;
            return history.isEmpty() || index < history.first() || index > history.last()
                    ? Optional.empty()
                    : Optional.of(history.get(index));
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
    }

    private static Optional<String> lastStartingWith(History history, String prefix) {
        ListIterator<History.Entry> it = history.iterator(history.size() + history.first());
        while (it.hasPrevious()) {
            var entry = it.previous();
            if (entry.line().startsWith(prefix)) {
                return Optional.of(entry.line());
            }
        }
        return Optional.empty();
    }
}
