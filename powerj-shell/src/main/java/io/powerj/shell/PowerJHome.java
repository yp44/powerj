package io.powerj.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Dossier de configuration de l'utilisateur ({@code ~/.powerj} par défaut, ou {@code POWERJ_HOME}).
 *
 * @param dir chemin absolu du dossier
 */
public record PowerJHome(Path dir) {

    static final String HOME_VARIABLE = "POWERJ_HOME";

    public PowerJHome {
        dir = Objects.requireNonNull(dir, "dir").toAbsolutePath();
    }

    /** Dossier défini par {@code POWERJ_HOME}, sinon {@code <userHome>/.powerj}. */
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

    /** Modules tiers chargés au démarrage (§4.4). */
    public Path modulesDir() {
        return dir.resolve("modules");
    }

    public Path logsDir() {
        return dir.resolve("logs");
    }

    /** Crée le dossier et ses sous-dossiers s'ils n'existent pas. */
    public PowerJHome createDirectories() throws IOException {
        Files.createDirectories(logsDir());
        return this;
    }
}
