package io.powerj.core.exec;

import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * State of a shell session: current directory, environment, variables, last native command.
 * Used from the command execution thread; the stages of a pipeline read it in parallel.
 */
public final class Session {

    /** Environment variables designating the home directory, in order of priority. */
    private static final List<String> HOME_VARIABLES = List.of("HOME", "USERPROFILE");

    private final Path home;
    private final Map<String, String> environment;
    private final Map<String, Object> variables = new HashMap<>();
    private Path currentDirectory;
    private Path previousDirectory;
    private NativeRun lastNative;
    private boolean lastSucceeded = true;
    private Integer exitRequest;
    private final JavaClasses java = new JavaClasses();
    /** Recent Java exceptions, most recent first ({@code $errors}, FR-53). */
    private final Deque<Throwable> errors = new ArrayDeque<>();
    private static final int MAX_ERRORS = 20;

    /**
     * @param home             user home directory, used for {@code ~} when the environment has neither
     *                         {@code HOME} nor {@code USERPROFILE}
     * @param currentDirectory initial current directory
     * @param environment      initial environment (copied)
     */
    public Session(Path home, Path currentDirectory, Map<String, String> environment) {
        this.home = home.toAbsolutePath().normalize();
        this.currentDirectory = currentDirectory.toAbsolutePath().normalize();
        // Environment variable names are case-insensitive on Windows.
        this.environment = Platform.isWindows() ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER) : new TreeMap<>();
        this.environment.putAll(environment);
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

    /** Environment of the session, applied to native commands (modifiable). */
    public Map<String, String> environment() {
        return environment;
    }

    public Optional<NativeRun> lastNative() {
        return Optional.ofNullable(lastNative);
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
        variables.put(name, value);
    }

    /** Names of the defined and automatic variables, sorted (completion). */
    public java.util.SortedSet<String> variableNames() {
        var names = new java.util.TreeSet<>(variables.keySet());
        names.addAll(List.of("?", "exit", "last", "pwd", "home", "errors"));
        return names;
    }

    /** Value of a variable, automatic ({@code $exit}, {@code $last}, {@code $?}…) or defined. */
    public Object variable(String name) {
        return switch (name) {
            case "?" -> lastSucceeded;
            case "exit" -> lastNative == null ? null : lastNative.exitCode();
            case "last" -> lastNative;
            case "pwd" -> currentDirectory;
            case "home" -> home;
            case "errors" -> recentErrors();
            default -> {
                if (!variables.containsKey(name)) {
                    throw new PjException(Messages.get("variable.unknown", name));
                }
                yield variables.get(name);
            }
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

    /** {@code $debug}: show the Java stack trace of errors (FR-43). */
    public boolean debug() {
        return Boolean.TRUE.equals(variables.get("debug"));
    }

    /** Requests that the shell close with this code ({@code exit}). */
    public void requestExit(int code) {
        exitRequest = code;
    }

    public OptionalInt exitRequest() {
        return exitRequest == null ? OptionalInt.empty() : OptionalInt.of(exitRequest);
    }
}
