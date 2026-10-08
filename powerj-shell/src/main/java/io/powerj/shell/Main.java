package io.powerj.shell;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.WriterOutputStream;

import io.powerj.core.BuildInfo;
import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.NativeEncoding;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.StandardInput;
import io.powerj.core.exec.Supervisor;

/**
 * Point d'entrée de {@code powerj.exe} : shell interactif sans argument ; {@code -c "ligne"} ou un fichier
 * {@code .pj} pour le mode non interactif ; {@code --debug} pour les détails des erreurs.
 */
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
        List<String> rest = Arrays.stream(args).filter(a -> !a.equals("--debug")).toList();
        if (rest.isEmpty()) {
            System.exit(run(home, ShellConfig.load(home.configFile())));
        }
        System.exit(runScript(rest));
    }

    /** {@code powerj -c "ligne"} ou {@code powerj fichier.pj} (FR-04d). */
    private static int runScript(List<String> args) {
        List<String> lines;
        if (args.getFirst().equals("-c")) {
            if (args.size() != 2) {
                System.err.println("usage : powerj -c \"<ligne>\"  |  powerj <fichier.pj>  |  powerj");
                return 2;
            }
            lines = List.of(args.get(1));
        } else if (args.size() == 1 && !args.getFirst().startsWith("-")) {
            Path file = Path.of(args.getFirst());
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("PowerJ : lecture impossible de " + file + " (" + e.getMessage() + ")");
                return 2;
            }
        } else {
            System.err.println("usage : powerj -c \"<ligne>\"  |  powerj <fichier.pj>  |  powerj");
            return 2;
        }
        boolean console = isInteractiveConsole();
        Charset outCharset = console ? charsetProperty("stdout.encoding") : StandardCharsets.UTF_8;
        var out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), outCharset), true);
        var err = new PrintWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.err), outCharset), true);
        Optional<Iterator<String>> input = Optional.empty();
        if (!StandardInput.isTerminal()) {
            // Les lignes reçues viennent en général d'une commande native (dir /b | powerj -c …) : même
            // décodage que pour elles (FR-40b), POWERJ_NATIVE_ENCODING_STDIN pour un réglage spécifique.
            Charset charset = new NativeEncoding().forProgram(Path.of("stdin"), System.getenv());
            var reader = new BufferedReader(new InputStreamReader(System.in, charset));
            input = Optional.of(reader.lines().iterator());
        }
        var session = new Session(Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath(),
                System.getenv());
        int width = console ? columns() : 10_000;
        var script = new ScriptMode(session, CmdletRegistry.discover(), out, err::println, width, input);
        script.loadModules(PowerJHome.resolve(System.getenv(), Path.of(System.getProperty("user.home"))).modulesDir());
        int code = script.run(lines);
        out.flush();
        err.flush();
        return code;
    }

    private static Charset charsetProperty(String name) {
        try {
            return Charset.forName(System.getProperty(name, System.getProperty("native.encoding", "UTF-8")));
        } catch (RuntimeException _) {
            return StandardCharsets.UTF_8;
        }
    }

    /** Largeur de la console ({@code COLUMNS}), 120 par défaut. */
    private static int columns() {
        try {
            return Integer.parseInt(System.getenv().getOrDefault("COLUMNS", "120"));
        } catch (NumberFormatException _) {
            return 120;
        }
    }

    private static int run(PowerJHome home, ShellConfig config) {
        Terminal terminal;
        try {
            var builder = TerminalBuilder.builder().system(true).name("PowerJ");
            // Entrée ou sortie redirigée (fichier, pipe) : UTF-8, quelle que soit la page de code du système.
            if (!isInteractiveConsole()) {
                builder.encoding(StandardCharsets.UTF_8).stdinEncoding(StandardCharsets.UTF_8)
                        .stdoutEncoding(StandardCharsets.UTF_8).stderrEncoding(StandardCharsets.UTF_8);
            }
            terminal = builder.build();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Terminal indisponible", e);
            System.err.println("PowerJ : terminal indisponible (" + e.getMessage() + ")");
            return 1;
        }
        // Le terminal est restauré (mode brut désactivé) même en cas d'arrêt brutal (FR-59).
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> close(terminal)));
        // System.out.println(…) appelé depuis une expression Java passe par le terminal JLine (FR-58).
        System.setOut(terminalStream(terminal));
        System.setErr(terminalStream(terminal));
        try {
            var reader = ShellReader.create(terminal, home, config);
            var session = new Session(Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath(),
                    System.getenv());
            var repl = new Repl(reader, new Supervisor(), session, CmdletRegistry.discover());
            repl.loadModules(home.modulesDir());
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

    private static PrintStream terminalStream(Terminal terminal) {
        return new PrintStream(new WriterOutputStream(terminal.writer(), terminal.encoding()), true,
                terminal.encoding());
    }

    private static boolean isInteractiveConsole() {
        var console = System.console();
        return console != null && console.isTerminal();
    }

    private static void close(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Fermeture du terminal", e);
        }
    }
}
