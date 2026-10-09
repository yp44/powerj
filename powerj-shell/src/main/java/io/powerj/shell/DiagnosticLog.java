package io.powerj.shell;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Diagnostic log in {@code <home>/logs} (specification FR-60): rotation over 5 files.
 * Nothing is written to the console, except in {@code --debug} mode, so as not to disturb the terminal.
 */
final class DiagnosticLog {

    private static final int FILE_LIMIT_BYTES = 1024 * 1024;
    private static final int FILE_COUNT = 5;

    private DiagnosticLog() {
    }

    static void install(Path logsDir, boolean debug) {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT %4$s %3$s - %5$s%6$s%n");
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        try {
            Handler file = new FileHandler(logsDir.resolve("powerj.%g.log").toString(),
                    FILE_LIMIT_BYTES, FILE_COUNT, true);
            file.setFormatter(new SimpleFormatter());
            file.setEncoding("UTF-8");
            root.addHandler(file);
        } catch (IOException | SecurityException e) {
            // No log possible: the shell remains usable.
            System.err.println(Messages.get("log.unavailable", logsDir, e.getMessage()));
        }
        if (debug) {
            var console = new ConsoleHandler();
            console.setLevel(Level.ALL);
            root.addHandler(console);
            root.setLevel(Level.FINE);
        }
    }
}
