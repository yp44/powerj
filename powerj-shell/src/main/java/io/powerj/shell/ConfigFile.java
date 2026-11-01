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

    /** One lock for every instance: two of them on the same file are as likely as one. */
    private static final Object WRITE_LOCK = new Object();

    private final Path file;

    ConfigFile(Path file) {
        this.file = file;
    }

    Path path() {
        return file;
    }

    /**
     * Every setting of the file; empty if the file is missing.
     *
     * @throws IOException if the file cannot be read, or is not valid properties syntax (a malformed unicode escape,
     *                     typically a Windows path written with single backslashes)
     */
    Properties read() throws IOException {
        var props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (NoSuchFileException _) {
            // no file, no setting
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        return props;
    }

    /** Value of {@code key}, empty if the file or the key is missing; see {@link #read()} for what can fail. */
    Optional<String> get(String key) throws IOException {
        return Optional.ofNullable(read().getProperty(key));
    }

    /**
     * Records {@code key=value}, creating the file if needed. Changes made at the same time by several parts of this
     * process do not lose one another (the file is read, changed and replaced under one lock); another process that
     * writes at the same moment is not protected.
     */
    void set(String key, String value) throws IOException {
        synchronized (WRITE_LOCK) {
            replace(key, value);
        }
    }

    private void replace(String key, String value) throws IOException {
        List<String> lines = new ArrayList<>();
        try {
            lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException _) {
            // new file
        }
        String line = escape(key, true) + "=" + escape(value, false);
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (key.equals(keyOf(lines.get(i)))) {
                lines.set(i, line);
                found = true;
            }
        }
        if (!found) {
            lines.add(line);
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
                char next = text.charAt(++i);
                switch (next) {
                    case 'n' -> key.append('\n');
                    case 't' -> key.append('\t');
                    case 'r' -> key.append('\r');
                    case 'f' -> key.append('\f');
                    case 'u' -> {
                        int digits = hexDigits(text, i + 1);
                        if (digits < 0) {
                            key.append(next); // not an escape: the letter stays
                        } else {
                            key.append((char) digits);
                            i += 4;
                        }
                    }
                    default -> key.append(next);
                }
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            } else {
                key.append(c);
            }
        }
        return key.toString();
    }

    /** The four hexadecimal digits at {@code from} as a number, or -1 if they are not there. */
    private static int hexDigits(String text, int from) {
        if (from + 4 > text.length()) {
            return -1;
        }
        int value = 0;
        for (int i = from; i < from + 4; i++) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }

    /**
     * The text as a properties file spells it, so that {@link Properties#load} gives it back unchanged. A backslash, a line
     * break and the characters that cannot be written are escaped; a space is escaped in a key, and at the start of a value
     * (load drops the blanks that follow the separator); {@code =}, {@code :}, {@code #} and {@code !} are escaped in a key. The
     * file is UTF-8, so letters of any language are written as they are, but a surrogate that is not part of a pair cannot be
     * encoded and is written as an escape.
     */
    static String escape(String text, boolean key) {
        var out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                case ' ' -> out.append(key || i == 0 ? "\\ " : " ");
                case '=', ':', '#', '!' -> out.append(key ? "\\" : "").append(c);
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < text.length()
                            && Character.isLowSurrogate(text.charAt(i + 1))) {
                        out.append(c).append(text.charAt(++i)); // a pair: a character outside the basic plane
                    } else if (c < 0x20 || c == 0x7f || Character.isSurrogate(c)) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
