package io.powerj.shell;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jline.terminal.Terminal;

import io.powerj.core.BuildInfo;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.Supervisor;

/** The interactive shell: opens the terminal, runs the {@link Repl} on it, saves the history and closes the terminal. */
final class InteractiveRun {

    private static final Logger LOG = Logger.getLogger(InteractiveRun.class.getName());

    private final Environment environment;
    private final ShellBootstrap.Terminals terminals;
    private final PowerJHome home;
    private final ShellConfig config;
    private final Session session;

    InteractiveRun(Environment environment, ShellBootstrap.Terminals terminals, PowerJHome home, ShellConfig config,
                   Session session) {
        this.environment = environment;
        this.terminals = terminals;
        this.home = home;
        this.config = config;
        this.session = session;
    }

    /** @return the exit code of the shell */
    int run(boolean runProfile) {
        Terminal terminal;
        try {
            terminal = terminals.open(environment);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Terminal unavailable", e);
            environment.messages().println(Messages.get("terminal.unavailable", e.getMessage()));
            return 1;
        }
        try {
            var reader = ShellReader.create(terminal, home, config);
            var repl = new Repl(reader, new Supervisor(), session, CmdletRegistry.discover());
            repl.nativeAccess(home.configFile());
            repl.loadModules(home.modulesDir());
            repl.useProfile(home.profileFile(), runProfile);
            int code = repl.run(BuildInfo.current());
            try {
                reader.getHistory().save();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Cannot save the history", e);
            }
            return code;
        } finally {
            close(terminal);
        }
    }

    static void close(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Closing the terminal", e);
        }
    }
}
