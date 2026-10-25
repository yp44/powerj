package io.powerj.core.exec;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Where the user is in a session: the current, previous and configuration directories, the home that {@code ~}
 * designates, and the environment. The command thread changes it while the stages of a pipeline, a background job or the
 * completion read it, so every field is {@code volatile} or a synchronized map (review C-13).
 */
final class SessionState {

    /** Environment variables designating the home directory, in order of priority. */
    private static final List<String> HOME_VARIABLES = List.of("HOME", "USERPROFILE");

    private final Path home;
    /** A synchronized map: each operation is atomic; to copy or iterate it, see {@link #environmentSnapshot()}. */
    private final Map<String, String> environment;
    private volatile Path currentDirectory;
    private volatile Path previousDirectory;
    private volatile Path configDirectory;

    SessionState(Path home, Path currentDirectory, Map<String, String> environment) {
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
    Path home() {
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

    /** The home given at startup, whatever {@code HOME} says since ({@code $home}). */
    Path startupHome() {
        return home;
    }

    /**
     * Tilde expansion of an unquoted word (FR-62): {@code ~} becomes the home directory, {@code ~/x} and
     * {@code ~\x} a path under it; any other word is returned unchanged ({@code ~user} included).
     */
    String expandTilde(String word) {
        if (word.equals("~")) {
            return home().toString();
        }
        if (word.startsWith("~/") || word.startsWith("~\\")) {
            return home().resolve(word.substring(2)).toString();
        }
        return word;
    }

    Path currentDirectory() {
        return currentDirectory;
    }

    Optional<Path> previousDirectory() {
        return Optional.ofNullable(previousDirectory);
    }

    /** Changes the current directory; the directory must exist. */
    void changeDirectory(Path target) {
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

    /** PowerJ configuration folder (§8). */
    Optional<Path> configDirectory() {
        return Optional.ofNullable(configDirectory);
    }

    void configDirectory(Path directory) {
        this.configDirectory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    /** The environment, modifiable; to iterate or copy it, hold its lock or use the snapshot. */
    Map<String, String> environment() {
        return environment;
    }

    /** A consistent copy that nobody changes: what a native command starts with. */
    Map<String, String> environmentSnapshot() {
        var copy = newEnvironment();
        synchronized (environment) {
            copy.putAll(environment);
        }
        return Collections.unmodifiableMap(copy);
    }
}
