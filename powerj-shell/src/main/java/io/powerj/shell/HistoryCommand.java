package io.powerj.shell;

import java.util.ArrayList;
import java.util.List;

import org.jline.reader.History;

import io.powerj.core.error.PjException;
import io.powerj.core.format.Values;
import io.powerj.core.runtime.Session;

/** {@code history} (numbered list, numbers reusable with {@code !n}) or {@code history --clear}. */
final class HistoryCommand {

    private final History history;

    HistoryCommand(History history) {
        this.history = history;
    }

    List<Object> run(List<Object> args, Session ignored) throws Exception {
        if (args.equals(List.of("--clear"))) {
            history.purge();
            return List.of();
        }
        if (!args.isEmpty()) {
            throw new PjException(Messages.get("history.unknownOption", Values.text(args.getLast())));
        }
        List<Object> lines = new ArrayList<>();
        for (var entry : history) {
            lines.add("%5d  %s".formatted(entry.index() + 1, entry.line()));
        }
        return lines;
    }
}
