package io.powerj.shell;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jline.terminal.Terminal;

import io.powerj.api.Language;
import io.powerj.core.BuildInfo;
import io.powerj.core.runtime.Session;

/**
 * Starts the shell: reads the command line, finds the home folder once, installs the log, reads the settings, sets the
 * language and builds the one {@link Session}, then runs the interactive shell or a script. It is the composition root
 * of the shell: {@link Main} only hands it the real process, and a test hands it a folder and a few streams.
 * <p>
 * The pieces that touch the machine are parameters: the {@link Environment} (variables, folders, streams), the way to
 * open a {@link Terminal} and the way to install the log.
 */
final class ShellBootstrap {

    /** Opens the terminal of the interactive shell. */
    @FunctionalInterface
    interface Terminals {
        Terminal open(Environment environment) throws IOException;
    }

    /** Installs the diagnostic log (FR-60); {@code warn} receives what the user should know about it. */
    @FunctionalInterface
    interface Logs {
        void install(Path logsDir, boolean debug, Consumer<String> warn);
    }

    /**
     * The command line.
     *
     * @param debug     {@code --debug}: details of the errors, and the log on the console
     * @param noProfile {@code --no-profile}: start without {@code profile.pj}
     * @param version   {@code --version}: print the version and stop
     * @param help      {@code --help}: print the usage and stop
     * @param script    what remains: {@code -c "line"}, a {@code .pj} file, or nothing for the interactive shell
     */
    record Options(boolean debug, boolean noProfile, boolean version, boolean help, List<String> script) {

        static Options parse(String... args) {
            boolean debug = false;
            boolean noProfile = false;
            boolean version = false;
            boolean help = false;
            List<String> script = new ArrayList<>();
            for (String arg : args) {
                switch (arg) {
                    case "--debug" -> debug = true;
                    case "--no-profile" -> noProfile = true;
                    case "--version" -> version = true;
                    case "--help", "-h" -> help = true;
                    default -> script.add(arg);
                }
            }
            return new Options(debug, noProfile, version, help, List.copyOf(script));
        }
    }

    private final Environment environment;
    private final Terminals terminals;
    private final Logs logs;

    ShellBootstrap(Environment environment, Terminals terminals, Logs logs) {
        this.environment = environment;
        this.terminals = terminals;
        this.logs = logs;
    }

    /** The shell of this process. */
    static ShellBootstrap system() {
        return new ShellBootstrap(Environment.system(), SystemTerminals::open, DiagnosticLog::install);
    }

    /** @return the exit code of the process */
    int execute(String... args) {
        Options options = Options.parse(args);
        if (options.help()) {
            return print(Messages.get("usage"));
        }
        if (options.version()) {
            return print(BuildInfo.current().banner());
        }
        PowerJHome home = PowerJHome.resolve(environment.variables(), environment.userHome());
        try {
            home.createDirectories();
        } catch (IOException e) {
            // Language not resolved yet: system locale (or -Dpowerj.language).
            environment.messages().println(Messages.get("home.createFailed", home.dir(), e.getMessage()));
        }
        logs.install(home.logsDir(), options.debug(), environment.messages()::println);
        ShellConfig config = ShellConfig.load(home.configFile(), environment.messages()::println);
        // Language of the messages (FR-61): -Dpowerj.language, POWERJ_LANG, config.properties, then the system.
        Language.set(Language.resolve(environment.properties().get(Language.PROPERTY),
                environment.variables().get(Language.ENVIRONMENT_VARIABLE), config.language(),
                environment.systemLocale()));
        var session = new Session(environment.userHome(), environment.workingDirectory(), environment.variables());
        session.configDirectory(home.dir());
        if (options.script().isEmpty()) {
            return new InteractiveRun(environment, terminals, home, config, session).run(!options.noProfile());
        }
        return new ScriptRun(environment, home, session).run(options.script());
    }

    private int print(String text) {
        var out = environment.stdoutWriter();
        out.println(text);
        out.flush();
        return 0;
    }
}
