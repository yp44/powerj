package io.powerj.shell;

import java.util.ListIterator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.reader.Expander;
import org.jline.reader.History;

/**
 * Expansion d'historique en début de ligne seulement (spécification FR-11) : {@code !!}, {@code !n}
 * et {@code !texte}, suivis éventuellement du reste de la ligne. Un {@code !} ailleurs dans la ligne
 * reste une négation ({@code where { !$_.dir }}).
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
        // JLine ignore l'exception et laisse la ligne telle quelle : le REPL signalera l'échec.
        return command.map(c -> c + m.group(2))
                .orElseThrow(() -> new IllegalArgumentException("!" + event + " : introuvable"));
    }

    @Override
    public String expandVar(String word) {
        return word;
    }

    /** Entrée numéro {@code n} telle qu'affichée par {@code history} (numérotation à partir de 1). */
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
