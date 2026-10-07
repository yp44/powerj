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

    /** Commande d'un groupe natif (étapes natives consécutives d'un pipeline). */
    public record Command(Path executable, List<String> args, boolean errorsToOutput) {
        public Command {
            args = List.copyOf(args);
        }
    }

    public Result run(Path executable, List<String> args, Session session, ShellIo io, boolean capture,
                      Optional<FileTarget> stdout, Optional<FileTarget> stderr) throws InterruptedException {
        return run(executable, args, session, io, capture, stdout, stderr, false, io.interactive());
    }

    /**
     * @param errorsToOutput {@code 2>&1} : stderr rejoint stdout
     * @param inheritInput   le process lit directement l'entrée standard du shell
     */
    public Result run(Path executable, List<String> args, Session session, ShellIo io, boolean capture,
                      Optional<FileTarget> stdout, Optional<FileTarget> stderr, boolean errorsToOutput,
                      boolean inheritInput) throws InterruptedException {
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

        builder.redirectInput(inheritInput && !detached
                ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
        builder.redirectErrorStream(errorsToOutput && !detached);
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
        if (builder.redirectError() == ProcessBuilder.Redirect.PIPE && !builder.redirectErrorStream()) {
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

    /**
     * Exécute des commandes natives reliées entre elles (FR-37) : les octets passent directement d'un process
     * au suivant, sans décodage.
     *
     * @param input        objets à écrire sur le stdin du premier process (forme affichée), ou {@code null}
     * @param inheritInput sans {@code input} : le premier process lit l'entrée standard du shell
     * @param output       étape suivante, qui reçoit les lignes du dernier process ; {@code null} si le groupe
     *                     termine le pipeline (les lignes vont alors à {@code sink}, ou au terminal / fichier)
     * @param errors       destination des lignes d'erreur quand elles ne sont pas héritées
     * @param stderrFile   fichier de {@code 2>} (en ajout : il est partagé par toutes les étapes)
     * @return métadonnées de chaque process, dans l'ordre
     */
    List<NativeRun> runGroup(List<Command> commands, Session session, ShellIo io, Source input, boolean inheritInput,
                             Pipe output, Consumer<Object> sink, boolean capture, Optional<FileTarget> stdout,
                             Optional<FileTarget> stderrFile, Consumer<String> errors) throws InterruptedException {
        List<ProcessBuilder> builders = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            Command command = commands.get(i);
            List<String> line = new ArrayList<>(command.args().size() + 1);
            line.add(command.executable().toString());
            line.addAll(command.args());
            var builder = new ProcessBuilder(line).directory(session.currentDirectory().toFile());
            builder.environment().clear();
            builder.environment().putAll(session.environment());
            if (i == 0) {
                builder.redirectInput(input == null && inheritInput
                        ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
            }
            if (i == commands.size() - 1) {
                builder.redirectOutput(output == null && stdout.isPresent() ? stdout.get().redirect()
                        : output == null && io.interactive() && !capture
                                ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
            }
            if (command.errorsToOutput()) {
                builder.redirectErrorStream(true);
            } else if (stderrFile.isPresent()) {
                builder.redirectError(ProcessBuilder.Redirect.appendTo(stderrFile.get().file().toFile()));
            } else if (io.interactive()) {
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            builders.add(builder);
        }

        long start = System.nanoTime();
        List<Process> processes;
        try {
            processes = ProcessBuilder.startPipeline(builders);
        } catch (IOException e) {
            throw new PjException(PjError.of("impossible de lancer " + commands.stream()
                    .map(c -> c.executable().getFileName().toString()).toList() + " : " + e.getMessage(), e));
        }
        Runnable stop = () -> processes.forEach(NativeRunner::destroyTree);
        if (output != null) {
            output.onAbort(stop);
        }

        List<Thread> threads = new ArrayList<>();
        Thread writer = null;
        Process first = processes.getFirst();
        if (input != null) {
            Charset charset = encoding.forProgram(commands.getFirst().executable(), session.environment());
            writer = Thread.ofVirtual().name("powerj-stdin").start(() -> writeObjects(input, first, charset, io));
        } else if (builders.getFirst().redirectInput() == ProcessBuilder.Redirect.PIPE) {
            closeQuietly(first.getOutputStream());
        }
        Process last = processes.getLast();
        if (builders.getLast().redirectOutput() == ProcessBuilder.Redirect.PIPE) {
            Charset charset = encoding.forProgram(commands.getLast().executable(), session.environment());
            Consumer<String> lines = output != null ? output::put : sink::accept;
            threads.add(pump(last.getInputStream(), charset, lines));
        }
        for (int i = 0; i < processes.size(); i++) {
            if (builders.get(i).redirectError() == ProcessBuilder.Redirect.PIPE && !builders.get(i).redirectErrorStream()) {
                Charset charset = encoding.forProgram(commands.get(i).executable(), session.environment());
                threads.add(pump(processes.get(i).getErrorStream(), charset, errors));
            }
        }

        List<NativeRun> runs = new ArrayList<>(processes.size());
        try {
            for (int i = 0; i < processes.size(); i++) {
                int exitCode = processes.get(i).waitFor();
                Command command = commands.get(i);
                runs.add(new NativeRun(command.executable(), command.args(), processes.get(i).pid(), exitCode,
                        elapsed(start)));
            }
            if (writer != null) {
                // Le premier process ne lit plus : l'étape qui l'alimente doit s'arrêter.
                input.abort();
                writer.interrupt();
                writer.join();
            }
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            stop.run();
            if (writer != null) {
                input.abort();
                writer.interrupt();
            }
            throw e;
        }
        return runs;
    }

    /** Écrit les objets reçus sur le stdin du process, sous leur forme affichée (FR-37). */
    private static void writeObjects(Source input, Process process, Charset charset, ShellIo io) {
        var writer = new java.io.PrintWriter(new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(process.getOutputStream(), charset)));
        var formatter = new OutputFormatter(writer, io.width().getAsInt());
        boolean complete = false;
        try {
            Object value;
            while ((value = input.next()) != Source.END) {
                formatter.accept(value);
                if (writer.checkError()) {
                    return; // le process a fermé son entrée
                }
            }
            complete = true;
        } catch (InterruptedException | java.util.concurrent.CancellationException _) {
            // arrêt du pipeline
        } finally {
            if (complete) {
                formatter.close();
            }
            writer.close();
            if (!complete) {
                input.abort();
            }
        }
    }

    private static Thread pump(InputStream stream, Charset charset, Consumer<String> sink) {
        return Thread.ofVirtual().name("powerj-pump").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.accept(line);
                }
            } catch (IOException | java.util.concurrent.CancellationException _) {
                // process arrêté, ou étape suivante fermée : fin de la lecture
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
