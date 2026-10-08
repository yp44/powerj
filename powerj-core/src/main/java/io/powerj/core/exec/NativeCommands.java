package io.powerj.core.exec;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Names of the programs found in the session's {@code PATH}, for completion (FR-21). On Windows,
 * the {@code PATHEXT} extensions are removed ({@code notepad}, not {@code notepad.exe}). The result
 * is cached as long as {@code PATH} and {@code PATHEXT} do not change, and for at most {@value #TTL_MILLIS} ms.
 */
public final class NativeCommands {

    private static final long TTL_MILLIS = 30_000;

    private final boolean windows;
    private String cachedKey;
    private long cachedAt;
    private Set<String> cached = Set.of();

    public NativeCommands() {
        this(Platform.isWindows());
    }

    NativeCommands(boolean windows) {
        this.windows = windows;
    }

    /** Sorted names of the programs in the {@code PATH}. */
    public synchronized Set<String> names(Map<String, String> environment) {
        String path = environment.getOrDefault("PATH", "");
        String pathext = environment.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        String key = path + "\u0000" + pathext;
        long now = System.currentTimeMillis();
        if (!key.equals(cachedKey) || now - cachedAt > TTL_MILLIS) {
            cached = scan(path, pathext);
            cachedKey = key;
            cachedAt = now;
        }
        return cached;
    }

    private Set<String> scan(String path, String pathext) {
        List<String> extensions = List.of(pathext.toLowerCase(Locale.ROOT).split(";"));
        Set<String> names = new TreeSet<>();
        for (String entry : path.split(Pattern.quote(windows ? ";" : ":"))) {
            if (entry.isBlank()) {
                continue;
            }
            Path dir;
            try {
                dir = Path.of(entry.strip());
            } catch (InvalidPathException _) {
                continue;
            }
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (windows) {
                        int dot = name.lastIndexOf('.');
                        if (dot > 0 && extensions.contains(name.substring(dot).toLowerCase(Locale.ROOT))) {
                            names.add(name.substring(0, dot));
                        }
                    } else if (Files.isRegularFile(file) && Files.isExecutable(file)) {
                        names.add(name);
                    }
                }
            } catch (IOException | SecurityException _) {
                // unreadable directory: ignored
            }
        }
        return java.util.Collections.unmodifiableSortedSet((TreeSet<String>) names);
    }
}
