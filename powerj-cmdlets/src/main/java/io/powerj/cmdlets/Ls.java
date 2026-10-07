package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/** {@code ls} : liste des fichiers sous forme d'objets {@link FileEntry} (spécification FR-35). */
@CmdletInfo(name = "ls", category = "Fichiers", summary = "Liste les fichiers et dossiers",
        examples = {"ls", "ls -r --filter *.java", "ls C:\\Windows -d", "(ls).name", "$f = ls; $f[0].size"})
public final class Ls implements Cmdlet<Ls.Params, Void, FileEntry> {

    /** Paramètres de {@code ls}. */
    public record Params(
            @Option(position = 0, description = "Dossiers ou fichiers à lister (jokers * et ? acceptés) ; défaut : dossier courant")
            List<String> paths,
            @Option(shortName = 'a', description = "Inclut les fichiers cachés")
            boolean all,
            @Option(shortName = 'r', description = "Parcourt les sous-dossiers")
            boolean recurse,
            @Option(shortName = 'f', description = "Ne garde que les noms correspondant au motif (ex. *.java)")
            String filter,
            @Option(shortName = 'd', description = "Uniquement les dossiers")
            boolean dirs,
            @Option(description = "Uniquement les fichiers")
            boolean files) {
    }

    private static final Comparator<Path> DIRECTORIES_FIRST = Comparator
            .comparing((Path p) -> !Files.isDirectory(p))
            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));

    @Override
    public void begin(Params params, CmdletContext<FileEntry> context) {
        if (params.dirs() && params.files()) {
            throw new IllegalArgumentException("--dirs et --files sont incompatibles");
        }
        Optional<PathMatcher> filter = Optional.ofNullable(params.filter()).map(Ls::glob);
        List<String> paths = params.paths().isEmpty() ? List.of(".") : params.paths();
        for (String path : paths) {
            list(path, params, filter, context);
        }
    }

    private void list(String argument, Params params, Optional<PathMatcher> filter, CmdletContext<FileEntry> context) {
        Path path;
        try {
            path = context.currentDirectory().resolve(argument).normalize();
        } catch (InvalidPathException _) {
            listWildcard(argument, params, filter, context);
            return;
        }
        if (argument.contains("*") || argument.contains("?")) {
            listWildcard(argument, params, filter, context);
        } else if (Files.isDirectory(path)) {
            listDirectory(path, params, filter, context);
        } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            entry(path).filter(e -> keep(e, params, filter)).ifPresent(context::emit);
        } else {
            context.error("introuvable : " + argument);
        }
    }

    /** {@code ls *.txt} ou {@code ls src/*.java} : joker sur le dernier élément du chemin. */
    private void listWildcard(String argument, Params params, Optional<PathMatcher> filter,
                              CmdletContext<FileEntry> context) {
        int separator = Math.max(argument.lastIndexOf('/'), argument.lastIndexOf('\\'));
        String dir = separator < 0 ? "." : argument.substring(0, separator + 1);
        PathMatcher pattern = glob(argument.substring(separator + 1));
        Path base = context.currentDirectory().resolve(dir).normalize();
        boolean found = false;
        for (Path child : children(base, params, context)) {
            if (pattern.matches(child.getFileName())) {
                found = true;
                entry(child).filter(e -> keep(e, params, filter)).ifPresent(context::emit);
                if (params.recurse() && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    listDirectory(child, params, filter, context);
                }
            }
        }
        if (!found) {
            context.error("aucun fichier ne correspond à " + argument);
        }
    }

    private void listDirectory(Path dir, Params params, Optional<PathMatcher> filter, CmdletContext<FileEntry> context) {
        for (Path child : children(dir, params, context)) {
            entry(child).filter(e -> keep(e, params, filter)).ifPresent(context::emit);
            // Pas de suivi des liens symboliques : évite les boucles infinies.
            if (params.recurse() && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                listDirectory(child, params, filter, context);
            }
        }
    }

    private static List<Path> children(Path dir, Params params, CmdletContext<FileEntry> context) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (params.all() || !hidden(child)) {
                    children.add(child);
                }
            }
        } catch (AccessDeniedException _) {
            context.error("accès refusé : " + dir);
        } catch (NoSuchFileException _) {
            context.error("introuvable : " + dir);
        } catch (IOException e) {
            context.error("lecture impossible de " + dir + " : " + e.getMessage());
        }
        children.sort(DIRECTORIES_FIRST);
        return children;
    }

    private static boolean keep(FileEntry entry, Params params, Optional<PathMatcher> filter) {
        if (params.dirs() && !entry.dir() || params.files() && entry.dir()) {
            return false;
        }
        return filter.map(f -> f.matches(entry.path().getFileName())).orElse(true);
    }

    private static Optional<FileEntry> entry(Path path) {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            boolean dir = attributes.isDirectory();
            return Optional.of(new FileEntry(name, dir ? 0 : attributes.size(),
                    attributes.lastModifiedTime().toInstant(), path.toAbsolutePath(), dir,
                    FileEntry.extensionOf(name, dir)));
        } catch (IOException _) {
            return Optional.empty(); // fichier disparu entre la liste et la lecture
        }
    }

    private static boolean hidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException _) {
            return false;
        }
    }

    private static PathMatcher glob(String pattern) {
        // Insensible à la casse sous Windows comme ailleurs : *.TXT trouve a.txt.
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.toLowerCase(Locale.ROOT));
        return p -> matcher.matches(Path.of(p.toString().toLowerCase(Locale.ROOT)));
    }
}
