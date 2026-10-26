package io.powerj.shell;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Settings read from {@code config.properties} (specification §8).
 *
 * @param historySize maximum number of history entries kept
 * @param language    {@code language} key ({@code en} or {@code fr}, FR-61), or {@code null} if absent
 */
public record ShellConfig(int historySize, String language) {

    private static final Logger LOG = Logger.getLogger(ShellConfig.class.getName());

    static final int DEFAULT_HISTORY_SIZE = 10_000;
    static final String HISTORY_SIZE_KEY = "history.size";
    static final String LANGUAGE_KEY = "language";

    public ShellConfig {
        if (historySize <= 0) {
            throw new IllegalArgumentException(Messages.get("config.historySize.notPositive", historySize));
        }
    }

    public static ShellConfig defaults() {
        return new ShellConfig(DEFAULT_HISTORY_SIZE, null);
    }

    /** Reads the file; missing or invalid values take their default value. */
    public static ShellConfig load(Path file) {
        return load(file, _ -> { });
    }

    /**
     * Reads the file; missing or invalid values take their default value. A file that cannot be read at all gives the
     * default settings, and {@code warn} is told why: the user should know that the file is not being applied.
     * That is the case of invalid UTF-8 and of a malformed unicode escape, which is what a Windows path written with
     * single backslashes (<code>C:&#92;users</code>) looks like to {@link Properties#load}.
     *
     * @param warn receives a one-line message for the user, in the language of the system (the language of the shell
     *             is not known yet: it is read from this file)
     */
    public static ShellConfig load(Path file, Consumer<String> warn) {
        Properties props;
        try {
            props = new ConfigFile(file).read();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read " + file + ", using default settings", e);
            warn.accept(Messages.get("config.unusable", file, e.getMessage()));
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
        LOG.warning(() -> "Invalid value for " + key + ": '" + text + "', using " + defaultValue);
        return defaultValue;
    }
}
