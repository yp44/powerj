package io.powerj.shell;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Consumer;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Diagnostic log in {@code <home>/logs} (specification FR-60): rotation over 5 files.
 * Nothing is written to the console, except in {@code --debug} mode, so as not to disturb the terminal.
 */
final class DiagnosticLog {

    private static final int FILE_LIMIT_BYTES = 1024 * 1024;
    private static final int FILE_COUNT = 5;

    private DiagnosticLog() {
    }

    /** @param warn receives the line to show when the log cannot be written; the shell stays usable */
    static void install(Path logsDir, boolean debug, Consumer<String> warn) {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        try {
            Handler file = new FileHandler(logsDir.resolve("powerj.%g.log").toString(),
                    FILE_LIMIT_BYTES, FILE_COUNT, true);
            file.setFormatter(new LineFormatter());
            file.setEncoding("UTF-8");
            root.addHandler(file);
        } catch (IOException | SecurityException e) {
            // No log possible: the shell remains usable.
            warn.accept(Messages.get("log.unavailable", logsDir, e.getMessage()));
        }
        if (debug) {
            var console = new ConsoleHandler();
            console.setLevel(Level.ALL);
            root.addHandler(console);
            root.setLevel(Level.FINE);
        }
    }

    /** {@code 2026-10-26 09:00:00 WARNING logger - message}, then the stack trace: the level is always in English. */
    private static final class LineFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String thrown = "";
            if (record.getThrown() != null) {
                var text = new StringWriter();
                try (var writer = new PrintWriter(text)) {
                    writer.println();
                    record.getThrown().printStackTrace(writer);
                }
                thrown = text.toString();
            }
            return "%1$tF %1$tT %2$s %3$s - %4$s%5$s%n".formatted(
                    ZonedDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault()), record.getLevel().getName(),
                    record.getLoggerName(), formatMessage(record), thrown);
        }
    }
}
