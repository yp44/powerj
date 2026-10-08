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
 * Noms des programmes trouvés dans le {@code PATH} de la session, pour la complétion (FR-21). Sous Windows,
 * les extensions de {@code PATHEXT} sont retirées ({@code notepad}, pas {@code notepad.exe}). Le résultat
 * est mis en cache tant que {@code PATH} et {@code PATHEXT} ne changent pas, et au plus {@value #TTL_MILLIS} ms.
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

    /** Noms triés des programmes du {@code PATH}. */
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
                // dossier illisible : ignoré
            }
        }
        return java.util.Collections.unmodifiableSortedSet((TreeSet<String>) names);
    }
}
