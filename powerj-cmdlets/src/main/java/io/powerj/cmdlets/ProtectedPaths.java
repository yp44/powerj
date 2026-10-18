package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Paths that {@code rm} and {@code mv} refuse to remove (specification FR-72): those of the {@value #FILE_NAME} file
 * of the configuration folder, any folder that contains one of them, and the current folder with its parents. The
 * file holds one path per line; {@code ~} is the home folder; empty lines and lines starting with {@code #} are
 * ignored. When it is missing, the defaults of the platform apply: a missing file never means "nothing protected".
 */
public final class ProtectedPaths {

    /** Name of the file in the configuration folder. */
    public static final String FILE_NAME = "protected-paths";

    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    private static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");

    private final List<Path> paths;
    private final Path currentDirectory;

    ProtectedPaths(List<Path> paths, Path currentDirectory) {
        this.paths = paths.stream().map(ProtectedPaths::key).toList();
        this.currentDirectory = key(currentDirectory);
    }

    /** Content of the file written at first launch: comments, then the defaults of the platform. */
    public static List<String> defaultFile(Path configDirectory) {
        List<String> lines = new ArrayList<>(List.of(
                "# Paths that rm and mv refuse to remove, nor any folder that contains one of them (PowerJ, FR-72).",
                "# One path per line; ~ is the home folder; lines starting with # are ignored.",
                "# The file is read at each command. To remove a protected path anyway: rm --allow-protected …",
                ""));
        lines.addAll(defaultEntries(configDirectory));
        return lines;
    }

    /** Default entries of the platform, as written in the file. */
    static List<String> defaultEntries(Path configDirectory) {
        List<String> entries = new ArrayList<>();
        FileSystems.getDefault().getRootDirectories().forEach(root -> entries.add(root.toString()));
        entries.add("~");
        entries.add(configDirectory == null ? "~/.powerj" : configDirectory.toAbsolutePath().normalize().toString());
        if (WINDOWS) {
            entries.addAll(List.of("C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)", "C:\\ProgramData",
                    "C:\\Users"));
        } else {
            entries.addAll(List.of("/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/opt", "/proc", "/root", "/sbin",
                    "/sys", "/usr", "/var"));
            if (MAC) {
                entries.addAll(List.of("/Applications", "/Library", "/System", "/Users"));
            }
        }
        return entries;
    }

    /** Protected paths of the configuration folder (or the defaults), for a command run in {@code currentDirectory}. */
    static ProtectedPaths load(Optional<Path> configDirectory, Map<String, String> environment, Path currentDirectory) {
        Path home = home(environment);
        List<String> entries = null;
        if (configDirectory.isPresent()) {
            Path file = configDirectory.get().resolve(FILE_NAME);
            try {
                entries = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException _) {
                // missing or unreadable: the defaults apply
            }
        }
        if (entries == null) {
            entries = defaultEntries(configDirectory.orElse(home.resolve(".powerj")));
        }
        List<Path> paths = new ArrayList<>();
        for (String entry : entries) {
            String line = entry.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                paths.add(line.equals("~") ? home
                        : line.startsWith("~/") || line.startsWith("~\\") ? home.resolve(line.substring(2))
                        : Path.of(line));
            } catch (InvalidPathException _) {
                // a line that is not a path protects nothing
            }
        }
        return new ProtectedPaths(paths, currentDirectory);
    }

    /** Why {@code target} must not be removed: a message, or empty if it may be removed. */
    Optional<String> refusal(Path target) {
        Path key = key(target);
        for (Path path : paths) {
            if (path.equals(key)) {
                return Optional.of(Messages.get("fileops.protected", target));
            }
            if (path.startsWith(key)) {
                return Optional.of(Messages.get("fileops.protected.contains", target, path));
            }
        }
        if (currentDirectory.startsWith(key)) {
            return Optional.of(Messages.get("fileops.current.directory", target));
        }
        return Optional.empty();
    }

    /** Home folder of the session: {@code HOME}, then {@code USERPROFILE}, then {@code user.home}. */
    private static Path home(Map<String, String> environment) {
        for (String name : List.of("HOME", "USERPROFILE")) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                try {
                    return Path.of(value.strip()).toAbsolutePath().normalize();
                } catch (InvalidPathException _) {
                    // try the next one
                }
            }
        }
        return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    }

    /** Path compared: absolute, normalized, and in lower case on Windows (case-insensitive file system). */
    private static Path key(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return WINDOWS ? Path.of(normalized.toString().toLowerCase(Locale.ROOT)) : normalized;
    }
}
