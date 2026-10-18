package io.powerj.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import io.powerj.cmdlets.ProtectedPaths;

/**
 * User configuration directory ({@code ~/.powerj} by default, or {@code POWERJ_HOME}).
 *
 * @param dir absolute path of the directory
 */
public record PowerJHome(Path dir) {

    static final String HOME_VARIABLE = "POWERJ_HOME";

    public PowerJHome {
        dir = Objects.requireNonNull(dir, "dir").toAbsolutePath();
    }

    /** Directory defined by {@code POWERJ_HOME}, otherwise {@code <userHome>/.powerj}. */
    public static PowerJHome resolve(Map<String, String> environment, Path userHome) {
        var configured = environment.get(HOME_VARIABLE);
        return new PowerJHome(configured != null && !configured.isBlank()
                ? Path.of(configured)
                : userHome.resolve(".powerj"));
    }

    public Path historyFile() {
        return dir.resolve("history");
    }

    public Path configFile() {
        return dir.resolve("config.properties");
    }

    /** Third-party modules loaded at startup (§4.4). */
    public Path modulesDir() {
        return dir.resolve("modules");
    }

    /** Commands run when the interactive shell starts (FR-63). */
    public Path profileFile() {
        return dir.resolve("profile.pj");
    }

    public Path logsDir() {
        return dir.resolve("logs");
    }

    /** Paths that {@code rm} and {@code mv} refuse to remove (FR-72). */
    public Path protectedPathsFile() {
        return dir.resolve(ProtectedPaths.FILE_NAME);
    }

    /**
     * Creates the directory and its subdirectories if they do not exist, and the {@code protected-paths} file with
     * the defaults of the platform if it does not exist (an existing file is never changed).
     */
    public PowerJHome createDirectories() throws IOException {
        Files.createDirectories(logsDir());
        if (Files.notExists(protectedPathsFile())) {
            Files.write(protectedPathsFile(), ProtectedPaths.defaultFile(dir));
        }
        return this;
    }
}
