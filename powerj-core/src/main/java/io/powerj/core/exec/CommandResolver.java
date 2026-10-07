package io.powerj.core.exec;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recherche d'un exécutable natif (spécification FR-13) : chemin explicite ({@code .\outil.exe},
 * {@code /usr/bin/git}) relatif au répertoire courant, sinon recherche dans le {@code PATH} de la session,
 * en essayant les extensions de {@code PATHEXT} sous Windows.
 */
public final class CommandResolver {

    private static final String DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    private final boolean windows;

    public CommandResolver() {
        this(Platform.isWindows());
    }

    CommandResolver(boolean windows) {
        this.windows = windows;
    }

    public Optional<Path> resolve(String name, Session session) {
        try {
            if (hasDirectory(name)) {
                return executable(session.currentDirectory().resolve(name), session);
            }
            for (String dir : pathEntries(session.environment())) {
                if (dir.isBlank()) {
                    continue;
                }
                var found = executable(session.currentDirectory().resolve(dir).resolve(name), session);
                if (found.isPresent()) {
                    return found;
                }
            }
        } catch (InvalidPathException _) {
            // nom ou entrée de PATH invalide : pas d'exécutable
        }
        return Optional.empty();
    }

    private boolean hasDirectory(String name) {
        return name.contains("/") || (windows && (name.contains("\\") || name.contains(":")));
    }

    private List<String> pathEntries(Map<String, String> environment) {
        String path = environment.getOrDefault("PATH", "");
        return List.of(path.split(Pattern.quote(windows ? ";" : ":")));
    }

    private Optional<Path> executable(Path candidate, Session session) {
        if (!windows) {
            return Files.isRegularFile(candidate) && Files.isExecutable(candidate)
                    ? Optional.of(candidate.toAbsolutePath().normalize())
                    : Optional.empty();
        }
        List<Path> candidates = new ArrayList<>();
        List<String> extensions = extensions(session.environment());
        String fileName = candidate.getFileName().toString().toUpperCase(Locale.ROOT);
        if (extensions.stream().anyMatch(fileName::endsWith)) {
            candidates.add(candidate);
        }
        for (String ext : extensions) {
            candidates.add(candidate.resolveSibling(candidate.getFileName() + ext.toLowerCase(Locale.ROOT)));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .map(p -> p.toAbsolutePath().normalize());
    }

    private static List<String> extensions(Map<String, String> environment) {
        String pathext = environment.getOrDefault("PATHEXT", DEFAULT_PATHEXT);
        List<String> result = new ArrayList<>();
        for (String ext : pathext.split(";")) {
            if (!ext.isBlank()) {
                result.add(ext.strip().toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }
}
