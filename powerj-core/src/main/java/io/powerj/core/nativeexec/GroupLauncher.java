package io.powerj.core.nativeexec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.powerj.fs.Host;

/**
 * EXPERIMENT of issue #128: starts the commands of a background job (FR-75) <b>in a process group of their own</b>, so
 * that the Ctrl+C of the terminal, which goes to the foreground group, never reaches them. There is no {@code sh} in
 * between and the pid is the program's. Used by {@code NativeRunner.startBackground} only when the session variable
 * {@link #VARIABLE} is {@value #GROUP}, and only on Linux; the rest of the time background commands are started as before.
 * <p>
 * One job is one process group, as in a shell with job control: the first stage of a pipeline is the leader and the
 * other stages join its group.
 */
public final class GroupLauncher {

    /** The session variable that turns the experiment on: {@code env --set POWERJ_BACKGROUND=group}. */
    public static final String VARIABLE = "POWERJ_BACKGROUND";
    /** The value of {@link #VARIABLE} that selects this launcher. */
    public static final String GROUP = "group";

    private static volatile Boolean available;

    private GroupLauncher() {
    }

    /** One command of the job. */
    public record Stage(Path executable, List<String> args, boolean errorsToOutput) {
        public Stage {
            Objects.requireNonNull(executable, "executable");
            args = List.copyOf(args);
        }
    }

    /** Where a stream of the job goes. */
    public record Output(Kind kind, Path file) {

        /** The kinds of destination. */
        public enum Kind { FILE, APPEND, INHERIT, DISCARD }

        /** The terminal of the shell. */
        public static Output inherit() {
            return new Output(Kind.INHERIT, null);
        }

        /** The null device. */
        public static Output discard() {
            return new Output(Kind.DISCARD, null);
        }

        /** A file, truncated first unless {@code append}. */
        public static Output file(Path file, boolean append) {
            return new Output(append ? Kind.APPEND : Kind.FILE, Objects.requireNonNull(file, "file"));
        }
    }

    /**
     * {@code true} if this system can start a job in a group of its own: Linux, with a libc that has the functions used
     * (glibc 2.34 or later). Anywhere else the caller keeps its usual way of starting a background command.
     */
    public static boolean available() {
        Boolean known = available;
        if (known == null) {
            known = probe();
            available = known;
        }
        return known;
    }

    private static boolean probe() {
        if (Host.windows() || Host.mac() || !System.getProperty("os.name", "").startsWith("Linux")) {
            return false;
        }
        try {
            return PosixSpawn.ready();
        } catch (Throwable _) {
            return false; // a function is missing from the libc: the class could not be initialised
        }
    }

    /**
     * Starts the stages of one job, connected by pipes, without waiting for them. Standard input is the null device.
     *
     * @param directory   the current directory of the session
     * @param environment the environment of the session, never that of the JVM
     * @param out         where the output of the last stage goes
     * @param err         where the error output of every stage goes, unless the stage has {@code 2>&1}
     * @return the processes, in order, each with its pid and its exit code
     * @throws IOException if a program cannot be started; the stages already started are then killed
     */
    public static List<Process> start(List<Stage> stages, Path directory, Map<String, String> environment, Output out,
                                      Output err) throws IOException {
        List<PosixSpawn.Spawned> started = new ArrayList<>(stages.size());
        int[] feeding = null; // the pipe whose read end is the standard input of the next stage
        int[] pipe = null;
        try {
            int group = PosixSpawn.OWN_GROUP;
            for (int i = 0; i < stages.size(); i++) {
                Stage stage = stages.get(i);
                boolean last = i == stages.size() - 1;
                pipe = last ? null : PosixSpawn.pipe();
                PosixSpawn.Spec spec = PosixSpawn.Spec.of(stage.executable(), stage.args().toArray(String[]::new))
                        .env(environment).directory(directory).group(group)
                        .in(feeding == null ? PosixSpawn.Stdio.NULL : PosixSpawn.Stdio.fd(feeding[0]))
                        .out(last ? stdio(out) : PosixSpawn.Stdio.fd(pipe[1]))
                        .err(stage.errorsToOutput() ? PosixSpawn.Stdio.STDOUT : stdio(err));
                PosixSpawn.Spawned spawned = PosixSpawn.spawnUnreaped(spec);
                started.add(spawned);
                if (i == 0) {
                    group = spawned.pid(); // the other stages join the group of the first
                }
                // The child has its own copies: the parent keeps only the read end that the next stage needs.
                closeQuietly(feeding == null ? -1 : feeding[0]);
                closeQuietly(pipe == null ? -1 : pipe[1]);
                feeding = pipe;
                pipe = null;
            }
        } catch (IOException | RuntimeException e) {
            started.forEach(s -> PosixSpawn.kill(s.pid(), PosixSpawn.SIGKILL));
            started.forEach(PosixSpawn::reap);
            throw e;
        } finally {
            closeQuietly(feeding == null ? -1 : feeding[0]);
            if (pipe != null) {
                closeQuietly(pipe[0]);
                closeQuietly(pipe[1]);
            }
        }
        // Only now, with every stage started and in its group, may an ended stage be reaped.
        started.forEach(PosixSpawn::reap);
        return started.stream().<Process>map(SpawnedProcess::new).toList();
    }

    private static PosixSpawn.Stdio stdio(Output output) {
        return switch (output.kind()) {
            case FILE -> PosixSpawn.Stdio.file(output.file());
            case APPEND -> PosixSpawn.Stdio.append(output.file());
            case INHERIT -> PosixSpawn.Stdio.INHERIT;
            case DISCARD -> PosixSpawn.Stdio.NULL;
        };
    }

    private static void closeQuietly(int fd) {
        if (fd >= 0) {
            PosixSpawn.close(fd);
        }
    }
}
