package io.powerj.fx.model;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/** The window state kept between two uses, in {@code ~/.powerj/fx-grid.properties}. Whatever goes wrong, the defaults apply. */
public final class WindowSettings {

    /** Name of the file, in the configuration folder of PowerJ. */
    public static final String FILE_NAME = "fx-grid.properties";

    private WindowSettings() {
    }

    /** The state recorded, if there is a usable one. */
    public static Optional<WindowState> load(Path file) {
        var properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
            var state = new WindowState(number(properties, "x"), number(properties, "y"), number(properties, "width"),
                    number(properties, "height"));
            return state.isUsable() ? Optional.of(state) : Optional.empty();
        } catch (NoSuchFileException | IllegalArgumentException _) {
            return Optional.empty();
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    private static double number(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException(key);
        }
        return Double.parseDouble(value.strip());
    }

    /** Records the state; a failure to write is not an error (the window just opens at the default place next time). */
    public static void save(Path file, WindowState state) {
        var properties = new Properties();
        properties.setProperty("x", Double.toString(state.x()));
        properties.setProperty("y", Double.toString(state.y()));
        properties.setProperty("width", Double.toString(state.width()));
        properties.setProperty("height", Double.toString(state.height()));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(out, "Window of the grid cmdlet");
            }
        } catch (IOException _) {
            // nothing to do: it is only a convenience
        }
    }
}
