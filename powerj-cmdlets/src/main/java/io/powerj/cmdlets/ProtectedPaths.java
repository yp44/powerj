package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Paths that {@code rm} and {@code mv} refuse to remove (specification FR-72): those of the {@value #FILE_NAME} file
 * of the configuration folder, any folder that contains one of them, and the current folder with its parents. The
 * file holds one path per line; {@code ~} is the home folder; empty lines and lines starting with {@code #} are
 * ignored. When it is missing, the defaults of the platform apply: a missing file never means "nothing protected".
 * <p>
 * Paths are compared as the file system sees them: the links of the folders above a target are resolved, so an alias
 * of a protected folder is refused as the folder is, and a protected entry that is itself a link protects what it points
 * to. Removing a link only removes the link, so the link itself is not resolved.
 * <p>
 * This guards against a mistake, such as removing a whole folder by typing the wrong path. It is not an access
 * control: the <em>content</em> of a protected folder is not protected ({@code rm /etc/hosts} is allowed), and a path
 * can still be changed between the check and the removal.
 */
public final class ProtectedPaths {

    /** Name of the file in the configuration folder. */
    public static final String FILE_NAME = "protected-paths";

    private final List<Path> paths;
    /** The current folder as written and with its links resolved (being in a link to a folder is being in it). */
    private final List<Path> currentDirectories;

    ProtectedPaths(List<Path> paths, Path currentDirectory) {
        this.paths = paths.stream().flatMap(path -> keys(path).stream()).distinct().toList();
        this.currentDirectories = keys(currentDirectory);
    }

    /**
     * Writes the file of the configuration folder, with the defaults of the platform, if it does not exist: an existing
     * file, edited or not, is never changed.
     */
    static void writeDefaultIfMissing(Path configDirectory) throws IOException {
        Path file = configDirectory.resolve(FILE_NAME);
        if (Files.notExists(file)) {
            Files.write(file, defaultFile(configDirectory));
        }
    }

    /** Content of the file written at first launch: comments, then the defaults of the platform. */
    static List<String> defaultFile(Path configDirectory) {
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
        if (HostOs.WINDOWS) {
            entries.addAll(List.of("C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)", "C:\\ProgramData",
                    "C:\\Users"));
        } else {
            entries.addAll(List.of("/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/opt", "/proc", "/root", "/sbin",
                    "/sys", "/usr", "/var"));
            if (HostOs.MAC) {
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
        Path key = RealPaths.of(target);
        for (Path path : paths) {
            if (path.equals(key)) {
                return Optional.of(Messages.get("fileops.protected", target));
            }
            if (path.startsWith(key)) {
                return Optional.of(Messages.get("fileops.protected.contains", target, path));
            }
        }
        for (Path current : currentDirectories) {
            if (current.startsWith(key)) {
                return Optional.of(Messages.get("fileops.current.directory", target));
            }
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

    /**
     * What a path stands for: itself, keyed as a target is (the links above it resolved, itself not followed), and what
     * it points to if it is a link ({@code /etc} is a link to {@code /private/etc} on macOS). Keyed the same way as the
     * targets, so that a path and its alias meet whatever the spelling of the folders (8.3 names on Windows).
     */
    private static List<Path> keys(Path path) {
        Path itself = RealPaths.of(path);
        Path pointedTo = RealPaths.ofFolder(path);
        return itself.equals(pointedTo) ? List.of(itself) : List.of(itself, pointedTo);
    }
}
