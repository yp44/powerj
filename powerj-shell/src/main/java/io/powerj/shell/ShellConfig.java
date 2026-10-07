package io.powerj.shell;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Réglages lus dans {@code config.properties} (spécification §8).
 *
 * @param historySize nombre maximal d'entrées d'historique conservées
 */
public record ShellConfig(int historySize) {

    private static final Logger LOG = Logger.getLogger(ShellConfig.class.getName());

    static final int DEFAULT_HISTORY_SIZE = 10_000;
    static final String HISTORY_SIZE_KEY = "history.size";

    public ShellConfig {
        if (historySize <= 0) {
            throw new IllegalArgumentException("history.size doit être positif : " + historySize);
        }
    }

    public static ShellConfig defaults() {
        return new ShellConfig(DEFAULT_HISTORY_SIZE);
    }

    /** Lit le fichier ; les valeurs absentes ou invalides prennent leur valeur par défaut. */
    public static ShellConfig load(Path file) {
        var props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (NoSuchFileException _) {
            return defaults();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Lecture de " + file + " impossible, réglages par défaut utilisés", e);
            return defaults();
        }
        return new ShellConfig(positiveInt(props, HISTORY_SIZE_KEY, DEFAULT_HISTORY_SIZE));
    }

    private static int positiveInt(Properties props, String key, int defaultValue) {
        var text = props.getProperty(key);
        if (text == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(text.strip());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException _) {
            // valeur invalide : signalée ci-dessous
        }
        LOG.warning(() -> "Valeur invalide pour " + key + " : '" + text + "', " + defaultValue + " utilisé");
        return defaultValue;
    }
}
