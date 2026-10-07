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
 * Journal de diagnostic dans {@code <home>/logs} (spécification FR-60) : rotation sur 5 fichiers.
 * Rien n'est écrit sur la console, sauf en mode {@code --debug}, pour ne pas perturber le terminal.
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
            // Pas de journal possible : le shell reste utilisable.
            System.err.println("PowerJ : journal indisponible dans " + logsDir + " (" + e.getMessage() + ")");
        }
        if (debug) {
            var console = new ConsoleHandler();
            console.setLevel(Level.ALL);
            root.addHandler(console);
            root.setLevel(Level.FINE);
        }
    }
}
