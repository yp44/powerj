package io.powerj.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

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

    public Path logsDir() {
        return dir.resolve("logs");
    }

    /** Creates the directory and its subdirectories if they do not exist. */
    public PowerJHome createDirectories() throws IOException {
        Files.createDirectories(logsDir());
        return this;
    }
}
