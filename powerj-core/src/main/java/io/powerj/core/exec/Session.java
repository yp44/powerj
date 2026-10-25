package io.powerj.core.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State of a shell session: current directory, environment, variables, last native command.
 * The command thread changes it while the stages of a pipeline, a background job or the completion read it, so every
 * field is either {@code volatile}, a concurrent collection, or guarded by the lock of the session (review C-13).
 * The fields that are written by the command thread only are {@code volatile} for visibility; none of them needs a
 * compound update.
 */
public final class Session {

    /** Environment variables designating the home directory, in order of priority. */
    private static final List<String> HOME_VARIABLES = List.of("HOME", "USERPROFILE");

    /** Stands for {@code null} in {@link #variables}, which does not hold null values. */
    private static final Object NULL = new Object();

    private final Path home;
    /** A synchronized map: each operation is atomic; to copy or iterate it, see {@link #environmentSnapshot()}. */
    private final Map<String, String> environment;
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private volatile Path currentDirectory;
    private volatile Path previousDirectory;
    private volatile Path configDirectory;
    private volatile NativeRun lastNative;
    private volatile boolean lastSucceeded = true;
    private volatile Integer exitRequest;
    private final JavaClasses java = new JavaClasses();
    private final Jobs jobs = new Jobs();
    /** Recent Java exceptions, most recent first ({@code $errors}, FR-53). */
    private final Deque<Throwable> errors = new ArrayDeque<>();
    private static final int MAX_ERRORS = 20;
    /** Lines measured in perf mode, most recent first (FR-76). */
    private final Deque<io.powerj.api.PerfReport> perfReports = new ArrayDeque<>();
    private int perfLines;
    static final int MAX_PERF_REPORTS = 50;

    /**
     * @param home             user home directory, used for {@code ~} when the environment has neither
     *                         {@code HOME} nor {@code USERPROFILE}
     * @param currentDirectory initial current directory
     * @param environment      initial environment (copied)
     */
    public Session(Path home, Path currentDirectory, Map<String, String> environment) {
        this.home = home.toAbsolutePath().normalize();
        this.currentDirectory = currentDirectory.toAbsolutePath().normalize();
        var copy = newEnvironment();
        copy.putAll(environment);
        this.environment = Collections.synchronizedMap(copy);
    }

    /** Environment variable names are case-insensitive on Windows. */
    private static TreeMap<String, String> newEnvironment() {
        return Platform.isWindows() ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER) : new TreeMap<>();
    }

    /**
     * Home directory, designated by {@code ~} (FR-62): the {@code HOME} variable of the session environment
     * (so {@code env --set HOME=…} applies at once), else {@code USERPROFILE} (Windows), else the user
     * directory given at startup.
     */
    public Path home() {
        for (String name : HOME_VARIABLES) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                try {
                    return Path.of(value.strip()).toAbsolutePath().normalize();
                } catch (InvalidPathException _) {
                    // invalid value: try the next source
                }
            }
        }
        return home;
    }

    /**
     * Tilde expansion of an unquoted word (FR-62): {@code ~} becomes the home directory, {@code ~/x} and
     * {@code ~\x} a path under it; any other word is returned unchanged ({@code ~user} included).
     */
    public String expandTilde(String word) {
        if (word.equals("~")) {
            return home().toString();
        }
        if (word.startsWith("~/") || word.startsWith("~\\")) {
            return home().resolve(word.substring(2)).toString();
        }
        return word;
    }

    public Path currentDirectory() {
        return currentDirectory;
    }

    /** PowerJ configuration folder (§8), given to the cmdlets ({@code CmdletContext.configDirectory()}). */
    public Optional<Path> configDirectory() {
        return Optional.ofNullable(configDirectory);
    }

    public void configDirectory(Path directory) {
        this.configDirectory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    public Optional<Path> previousDirectory() {
        return Optional.ofNullable(previousDirectory);
    }

    /** Changes the current directory; the directory must exist. */
    public void changeDirectory(Path target) {
        Path resolved = currentDirectory.resolve(target).toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            throw new PjException(Messages.get("cd.notFound", target));
        }
        if (!Files.isDirectory(resolved)) {
            throw new PjException(Messages.get("cd.notDirectory", target));
        }
        previousDirectory = currentDirectory;
        currentDirectory = resolved;
    }

    /**
     * Environment of the session (modifiable). Each operation on the map is atomic; iterating over it, or copying it,
     * must be done while holding its lock ({@code synchronized (map)}), or on {@link #environmentSnapshot()}.
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * A consistent copy of the environment that nobody changes: what a native command starts with. Names keep the case
     * rule of the platform.
     */
    public Map<String, String> environmentSnapshot() {
        var copy = newEnvironment();
        synchronized (environment) {
            copy.putAll(environment);
        }
        return Collections.unmodifiableMap(copy);
    }

    public Optional<NativeRun> lastNative() {
        return Optional.ofNullable(lastNative);
    }

    /** Commands launched in the background with {@code &} (FR-75). */
    public Jobs jobs() {
        return jobs;
    }

    void recordNative(NativeRun run) {
        lastNative = Objects.requireNonNull(run);
    }

    public boolean lastSucceeded() {
        return lastSucceeded;
    }

    void recordSuccess(boolean succeeded) {
        lastSucceeded = succeeded;
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value == null ? NULL : value);
    }

    /** Names of the defined and automatic variables, sorted (completion). */
    public SortedSet<String> variableNames() {
        var names = new TreeSet<>(variables.keySet());
        names.addAll(AutomaticVariable.names());
        return names;
    }

    /** Value of a variable, automatic ({@code $exit}, {@code $last}, {@code $?}…) or defined. */
    public Object variable(String name) {
        Optional<AutomaticVariable> automatic = AutomaticVariable.named(name);
        if (automatic.isPresent()) {
            return value(automatic.get());
        }
        Object value = variables.get(name);
        if (value == null) {
            throw new PjException(Messages.get("variable.unknown", name));
        }
        return value == NULL ? null : value;
    }

    private Object value(AutomaticVariable variable) {
        NativeRun last = lastNative; // read once: another thread may record a new one
        return switch (variable) {
            case SUCCEEDED -> lastSucceeded;
            case EXIT -> last == null ? null : last.exitCode();
            case LAST -> last;
            case PWD -> currentDirectory;
            case HOME -> home;
            case ERRORS -> recentErrors();
            case PERF -> perfReports().stream().findFirst().orElse(null);
        };
    }

    /** Accessible Java classes and imports of the session (FR-47). */
    public JavaClasses java() {
        return java;
    }

    /** Keeps the original exception of an error for {@code $errors} (FR-53). */
    public synchronized void recordError(Throwable error) {
        errors.addFirst(error);
        while (errors.size() > MAX_ERRORS) {
            errors.removeLast();
        }
    }

    public synchronized List<Throwable> recentErrors() {
        return List.copyOf(errors);
    }

    /** Number of the next line measured in perf mode. */
    synchronized int nextPerfLine() {
        return ++perfLines;
    }

    /** Keeps the report of a measured line, for {@code perf} and {@code $perf} (FR-76). */
    synchronized void addPerfReport(io.powerj.api.PerfReport report) {
        perfReports.addFirst(report);
        while (perfReports.size() > MAX_PERF_REPORTS) {
            var dropped = perfReports.removeLast();
            if (dropped.recording() != null) {
                try {
                    Files.deleteIfExists(dropped.recording());
                } catch (IOException _) {
                    // a temporary file: removed at exit
                }
            }
        }
    }

    /** The lines measured in perf mode, most recent first. */
    public synchronized List<io.powerj.api.PerfReport> perfReports() {
        return List.copyOf(perfReports);
    }

    /** {@code $debug}: show the Java stack trace of errors (FR-43). */
    public boolean debug() {
        return Boolean.TRUE.equals(variables.get("debug"));
    }

    /** Requests that the shell close with this code ({@code exit}). */
    public void requestExit(int code) {
        exitRequest = code;
    }

    public OptionalInt exitRequest() {
        Integer code = exitRequest;
        return code == null ? OptionalInt.empty() : OptionalInt.of(code);
    }
}
