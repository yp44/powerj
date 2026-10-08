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
 * Settings read from {@code config.properties} (specification §8).
 *
 * @param historySize maximum number of history entries kept
 * @param language    {@code language} key ({@code en} or {@code fr}, FR-60), or {@code null} if absent
 */
public record ShellConfig(int historySize, String language) {

    private static final Logger LOG = Logger.getLogger(ShellConfig.class.getName());

    static final int DEFAULT_HISTORY_SIZE = 10_000;
    static final String HISTORY_SIZE_KEY = "history.size";
    static final String LANGUAGE_KEY = "language";

    public ShellConfig {
        if (historySize <= 0) {
            throw new IllegalArgumentException("history.size doit être positif : " + historySize);
        }
    }

    public static ShellConfig defaults() {
        return new ShellConfig(DEFAULT_HISTORY_SIZE, null);
    }

    /** Reads the file; missing or invalid values take their default value. */
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
        return new ShellConfig(positiveInt(props, HISTORY_SIZE_KEY, DEFAULT_HISTORY_SIZE), props.getProperty(LANGUAGE_KEY));
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
            // invalid value: reported below
        }
        LOG.warning(() -> "Valeur invalide pour " + key + " : '" + text + "', " + defaultValue + " utilisé");
        return defaultValue;
    }
}
