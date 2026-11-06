package io.powerj.core.nativeexec;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.powerj.fs.Host;

/**
 * Starts the commands of a background job (FR-75) <b>in a process group of their own</b>, so that the Ctrl+C of the
 * terminal, which goes to the foreground group, never reaches them (issue #128). Java's {@link ProcessBuilder} cannot choose
 * the process group, so the job is started through the FFM API: {@code posix_spawn} on Linux and macOS
 * ({@link PosixJobs}), {@code CreateProcessW} with {@code CREATE_NEW_PROCESS_GROUP} on Windows ({@link WindowsJobs}).
 * There is no {@code sh} in between and the pid is the program's.
 * <p>
 * One job is one process group on Unix, as in a shell with job control: the first stage of a pipeline is the leader and the
 * other stages join its group. On Windows each stage is a group of its own, which is what disables Ctrl+C for it.
 * <p>
 * Where the system cannot do it ({@link #available()}: a libc without the functions, an unknown system) or the program
 * cannot be started this way ({@link #handles}: a Windows batch file), the caller starts the job as it can: on Windows a
 * batch file by {@link ProcessBuilder}, without protection; on Unix, only when the session variable
 * {@value #SH_FALLBACK_VARIABLE} is {@code true}, through {@code sh}, which ignores the signals and replaces itself with
 * the program.
 */
public final class BackgroundLauncher {

    /**
     * The session variable that allows the {@code sh} fallback on a Unix system that cannot use {@code posix_spawn}
     * as needed (glibc older than 2.34, musl): {@code POWERJ_BACKGROUND_SH_FALLBACK=true}. Without it, a background command
     * there is refused with a message that names the variable.
     */
    public static final String SH_FALLBACK_VARIABLE = "POWERJ_BACKGROUND_SH_FALLBACK";

    private BackgroundLauncher() {
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
     * {@code true} if this system can start a job in a group of its own: Windows, Linux (glibc 2.34 or later) and macOS
     * (10.15 or later). Asked once, the first time it is needed.
     */
    public static boolean available() {
        return Probe.AVAILABLE;
    }

    /** Asked once, the first time it is needed: loading {@link PosixSpawn} or {@link WindowsSpawn} binds the system functions. */
    private static final class Probe {
        static final boolean AVAILABLE = probe();

        private Probe() {
        }
    }

    private static boolean probe() {
        try {
            if (Host.windows()) {
                return WindowsSpawn.ready();
            }
            return (Host.mac() || Host.linux()) && PosixSpawn.ready();
        } catch (Throwable _) {
            return false; // a function is missing from the system: the class could not be initialised
        }
    }

    /**
     * {@code true} if this job can be started by {@link #start}: the system can ({@link #available()}) and no stage is a
     * Windows batch file, which {@code cmd.exe} reads by rules of its own that {@code CreateProcessW} cannot apply safely.
     */
    public static boolean handles(List<Stage> stages) {
        return available() && !(Host.windows() && stages.stream().anyMatch(stage -> isBatchFile(stage.executable())));
    }

    private static boolean isBatchFile(Path executable) {
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".bat") || name.endsWith(".cmd");
    }

    /** {@code true} if the environment of the session allows the {@code sh} fallback ({@value #SH_FALLBACK_VARIABLE}). */
    public static boolean shFallbackAllowed(Map<String, String> environment) {
        return "true".equalsIgnoreCase(environment.get(SH_FALLBACK_VARIABLE));
    }

    /**
     * Opens, and closes at once, the file of a redirection, so that a target that cannot be opened (a folder that does not
     * exist, no right to write) fails <b>before</b> anything starts, with the name of the file and the reason, as
     * {@link ProcessBuilder} did. Inside {@code posix_spawn} or {@code CreateProcessW} the same failure would be reported as
     * a program that cannot be started. A file to truncate is truncated here, and again by the first stage: no harm.
     */
    private static void open(Output output) throws IOException {
        if (output.kind() == Output.Kind.FILE || output.kind() == Output.Kind.APPEND) {
            new FileOutputStream(output.file().toFile(), output.kind() == Output.Kind.APPEND).close();
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
        open(out);
        open(err);
        return Host.windows() ? WindowsJobs.start(stages, directory, environment, out, err)
                : PosixJobs.start(stages, directory, environment, out, err);
    }
}
