package io.powerj.core.exec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Exécute une commande native (spécification §3.10). Les flux restent des flux : stdout et stderr ne
 * sont jamais encapsulés dans un objet.
 * <ul>
 *   <li>affichage sur un vrai terminal : stdin/stdout/stderr hérités (couleurs, {@code vim}, {@code ssh}) ;</li>
 *   <li>capture ({@code $x = cmd}) : stdout lu en lignes {@code String}, stderr vers le flux d'erreur ;</li>
 *   <li>redirections {@code >}, {@code >>}, {@code 2>}, {@code 2>>} : octets bruts vers le fichier ;</li>
 *   <li>application graphique Windows : lancée détachée (FR-39).</li>
 * </ul>
 * Ctrl+C (interruption du fil) arrête le process et tous ses descendants (FR-57).
 */
public final class NativeRunner {

    private static final long GRACE_SECONDS = 2;

    /** Fichier cible d'une redirection. */
    public record FileTarget(Path file, boolean append) {
        ProcessBuilder.Redirect redirect() {
            return append ? ProcessBuilder.Redirect.appendTo(file.toFile()) : ProcessBuilder.Redirect.to(file.toFile());
        }
    }

    /** Résultat : métadonnées, et lignes de stdout si elles ont été capturées. */
    public record Result(NativeRun run, List<String> capturedLines) { }

    private final NativeEncoding encoding;

    public NativeRunner() {
        this(new NativeEncoding());
    }

    NativeRunner(NativeEncoding encoding) {
        this.encoding = encoding;
    }

    public Result run(Path executable, List<String> args, Session session, ShellIo io, boolean capture,
                      Optional<FileTarget> stdout, Optional<FileTarget> stderr) throws InterruptedException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable.toString());
        command.addAll(args);
        var builder = new ProcessBuilder(command).directory(session.currentDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(session.environment());
        Charset charset = encoding.forProgram(executable, session.environment());

        boolean detached = !capture && stdout.isEmpty() && stderr.isEmpty()
                && Platform.isWindows() && ExecutableKind.isWindowsGui(executable);
        boolean inheritOut = io.interactive() && !capture && stdout.isEmpty() && !detached;
        boolean inheritErr = io.interactive() && !capture && stderr.isEmpty() && !detached;

        builder.redirectInput(io.interactive() && !detached
                ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(detached ? ProcessBuilder.Redirect.DISCARD
                : stdout.map(FileTarget::redirect).orElse(inheritOut
                        ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE));
        builder.redirectError(detached ? ProcessBuilder.Redirect.DISCARD
                : stderr.map(FileTarget::redirect).orElse(inheritErr
                        ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE));

        long start = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new PjException(PjError.of("impossible de lancer " + executable + " : " + e.getMessage(), e));
        }
        if (builder.redirectInput() == ProcessBuilder.Redirect.PIPE) {
            closeQuietly(process.getOutputStream());
        }
        if (detached) {
            var run = new NativeRun(executable, args, process.pid(), null, elapsed(start));
            return new Result(run, List.of());
        }

        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        List<Thread> pumps = new ArrayList<>(2);
        if (builder.redirectOutput() == ProcessBuilder.Redirect.PIPE) {
            Consumer<String> sink = capture ? captured::add : line -> {
                io.out().println(line);
                io.out().flush();
            };
            pumps.add(pump(process.getInputStream(), charset, sink));
        }
        if (builder.redirectError() == ProcessBuilder.Redirect.PIPE) {
            pumps.add(pump(process.getErrorStream(), charset, io.errors()));
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
            for (Thread pump : pumps) {
                pump.join();
            }
        } catch (InterruptedException e) {
            destroyTree(process);
            throw e;
        }
        var run = new NativeRun(executable, args, process.pid(), exitCode, elapsed(start));
        return new Result(run, List.copyOf(captured));
    }

    private static Thread pump(InputStream stream, Charset charset, Consumer<String> sink) {
        return Thread.ofVirtual().name("powerj-pump").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.accept(line);
                }
            } catch (IOException _) {
                // process arrêté : fin de la lecture
            }
        });
    }

    /** Arrête le process et ses descendants, de force s'ils ne se terminent pas à temps. */
    private static void destroyTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException _) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static void closeQuietly(java.io.OutputStream stream) {
        try {
            stream.close();
        } catch (IOException _) {
            // rien à faire
        }
    }
}
