package io.powerj.shell;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
     * Reads the file; missing values take their default value, and so do invalid ones, which {@code warn} is told
     * about. A file that cannot be read at all gives the default settings, and {@code warn} is told why: the user
     * should know that the file, or a line of it, is not being applied.
     * That is the case of invalid UTF-8 and of a malformed unicode escape, which is what a Windows path written with
     * single backslashes (<code>C:&#92;users</code>) looks like to {@link Properties#load}.
     *
     * @param warn receives a one-line message for the user, in the language in force when {@code load} runs (the
     *             system's: the language of the shell is read from this file); {@link ShellBootstrap} uses
     *             {@link #loadWarning} instead, to word them in the language of the file
     */
    public static ShellConfig load(Path file, Consumer<String> warn) {
        return loadWarning(file, message -> warn.accept(message.get()));
    }

    /**
     * Reads the file like {@link #load(Path, Consumer)}, but hands each warning over as a text that is worded when it is
     * asked for: the caller asks once the language of the shell is known, so that {@code language=fr} and a bad
     * {@code history.size} in the same file give a warning in French.
     */
    static ShellConfig loadWarning(Path file, Consumer<Supplier<String>> warn) {
        Properties props;
        try {
            props = new ConfigFile(file).read();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read " + file + ", using default settings", e);
            warn.accept(() -> Messages.get("config.unusable", file, e.getMessage()));
            return defaults();
        }
        return new ShellConfig(positiveInt(props, HISTORY_SIZE_KEY, DEFAULT_HISTORY_SIZE, warn),
                props.getProperty(LANGUAGE_KEY));
    }

    private static int positiveInt(Properties props, String key, int defaultValue, Consumer<Supplier<String>> warn) {
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
        // The user should know that the line is not being applied, as for a file that cannot be read (U-13): that is the
        // console; the log keeps a line at FINE, not a second warning for the same thing.
        LOG.fine(() -> "Invalid value for " + key + ": '" + text + "', using " + defaultValue);
        warn.accept(() -> Messages.get("config.invalidValue", key, text, defaultValue));
        return defaultValue;
    }
}
