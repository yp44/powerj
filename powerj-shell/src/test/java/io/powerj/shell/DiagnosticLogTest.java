package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Diagnostic log in the logs folder (FR-60); the console only in debug mode. */
class DiagnosticLogTest {

    @TempDir
    Path tmp;

    /** Closes the log files (a file left open cannot be removed on Windows) and restores the default logging. */
    @AfterEach
    void restoreLogging() throws Exception {
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            handler.close();
        }
        LogManager.getLogManager().readConfiguration();
    }

    @Test
    void writesToAFileAndNotToTheConsole() throws Exception {
        DiagnosticLog.install(tmp, false, _ -> { });
        Logger.getLogger("test").warning("something to know");
        Logger root = Logger.getLogger("");
        assertThat(root.getHandlers()).singleElement().isInstanceOf(FileHandler.class);
        root.getHandlers()[0].flush();
        assertThat(Files.readString(tmp.resolve("powerj.0.log"))).contains("WARNING test - something to know");
    }

    @Test
    void aStackTraceFollowsTheLineAndTheLevelIsInEnglishWhateverTheLanguage() throws Exception {
        java.util.Locale before = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.FRENCH);
        try {
            DiagnosticLog.install(tmp, false, _ -> { });
            Logger.getLogger("test").log(Level.WARNING, "went wrong {0}", new IllegalStateException("boom"));
            Logger.getLogger("").getHandlers()[0].flush();
        } finally {
            java.util.Locale.setDefault(before);
        }

        String log = Files.readString(tmp.resolve("powerj.0.log"));
        assertThat(log).contains("WARNING test - went wrong {0}").contains("java.lang.IllegalStateException: boom")
                .contains("\tat ");
    }

    @Test
    void debugAlsoWritesFineMessagesToTheConsole() {
        DiagnosticLog.install(tmp, true, _ -> { });
        Logger root = Logger.getLogger("");
        assertThat(root.getLevel()).isEqualTo(Level.FINE);
        assertThat(root.getHandlers()).hasAtLeastOneElementOfType(ConsoleHandler.class);
    }

    @Test
    void withoutALogsFolderTheShellStaysUsable() throws Exception {
        Path notAFolder = Files.writeString(tmp.resolve("file"), "x");
        List<String> warnings = new ArrayList<>();

        DiagnosticLog.install(notAFolder, false, warnings::add);

        assertThat(Logger.getLogger("").getHandlers()).isEmpty();
        assertThat(warnings).singleElement().asString().contains(notAFolder.toString());
    }
}
