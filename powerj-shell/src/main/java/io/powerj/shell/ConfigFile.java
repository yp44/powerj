package io.powerj.shell;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * {@code config.properties}, read and changed one setting at a time. A change keeps the comments, the order and the other
 * settings of the user: only the line of the key is replaced, or added at the end.
 */
final class ConfigFile {

    private final Path file;

    ConfigFile(Path file) {
        this.file = file;
    }

    Path path() {
        return file;
    }

    /** Value of {@code key}, empty if the file or the key is missing. */
    Optional<String> get(String key) throws IOException {
        var props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (NoSuchFileException _) {
            return Optional.empty();
        }
        return Optional.ofNullable(props.getProperty(key));
    }

    /** Records {@code key=value}, creating the file if needed. */
    void set(String key, String value) throws IOException {
        List<String> lines = new ArrayList<>();
        try {
            lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException _) {
            // new file
        }
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (key.equals(keyOf(lines.get(i)))) {
                lines.set(i, key + "=" + value);
                found = true;
            }
        }
        if (!found) {
            lines.add(key + "=" + value);
        }
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "config", ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Key defined by a line of a properties file, or {@code null} for a blank line or a comment. */
    static String keyOf(String line) {
        String text = line.stripLeading();
        if (text.isEmpty() || text.charAt(0) == '#' || text.charAt(0) == '!') {
            return null;
        }
        var key = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                key.append(text.charAt(++i));
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            } else {
                key.append(c);
            }
        }
        return key.toString();
    }
}
