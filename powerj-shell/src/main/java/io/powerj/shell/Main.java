package io.powerj.shell;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import io.powerj.core.BuildInfo;
import io.powerj.core.exec.Supervisor;

/** Point d'entrée de {@code powerj.exe}. */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) {
        boolean debug = Arrays.asList(args).contains("--debug");
        var home = PowerJHome.resolve(System.getenv(), Path.of(System.getProperty("user.home")));
        try {
            home.createDirectories();
        } catch (IOException e) {
            System.err.println("PowerJ : impossible de créer " + home.dir() + " (" + e.getMessage() + ")");
        }
        DiagnosticLog.install(home.logsDir(), debug);
        System.exit(run(home, ShellConfig.load(home.configFile())));
    }

    private static int run(PowerJHome home, ShellConfig config) {
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).name("PowerJ").build();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Terminal indisponible", e);
            System.err.println("PowerJ : terminal indisponible (" + e.getMessage() + ")");
            return 1;
        }
        // Le terminal est restauré (mode brut désactivé) même en cas d'arrêt brutal (FR-59).
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> close(terminal)));
        try {
            var reader = ShellReader.create(terminal, home, config);
            var repl = new Repl(reader, new Supervisor(), () -> Path.of("").toAbsolutePath());
            int code = repl.run(BuildInfo.current());
            try {
                reader.getHistory().save();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Sauvegarde de l'historique impossible", e);
            }
            return code;
        } finally {
            close(terminal);
        }
    }

    private static void close(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Fermeture du terminal", e);
        }
    }
}
