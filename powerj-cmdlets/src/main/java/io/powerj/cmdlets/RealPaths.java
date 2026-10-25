package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Paths as the file system sees them, to compare them (review C-21). A path written with {@code ..} after a link, an
 * alias of a protected folder, a link in the middle of the path: {@link Path#normalize()} knows none of that.
 */
final class RealPaths {

    private RealPaths() {
    }

    /**
     * The path of an element to act on, to compare with others: absolute, with the links and the {@code ..} of the
     * folders above it resolved by the file system. The element itself is <em>not</em> resolved, because acting on a link
     * (removing it, moving it) acts on the link and not on what it points to. The part that does not exist yet (a
     * destination) is kept as written. If the folders cannot be resolved the lexical path is returned.
     * <p>
     * The order matters: {@code alias/..} is the parent of what {@code alias} points to, not the folder that holds
     * {@code alias}, so the path is resolved before it is normalized, never after.
     */
    static Path of(Path path) {
        Path absolute = path.toAbsolutePath();
        Path last = absolute.getFileName();
        if (last == null) {
            return compared(absolute); // a root
        }
        if (last.toString().equals("..") || last.toString().equals(".")) {
            return compared(resolveFolders(absolute)); // the file system says where it goes
        }
        return compared(resolveFolders(absolute.getParent()).resolve(last));
    }

    /**
     * The folder itself, with every link resolved: for the current folder, where being in a link to a protected folder
     * is being in that folder. The lexical path if it cannot be resolved.
     */
    static Path ofFolder(Path folder) {
        return compared(resolveFolders(folder.toAbsolutePath()));
    }

    /**
     * {@code folder} with its links and {@code ..} resolved as far as it exists. What does not exist cannot be a link,
     * so it is appended as written and normalized as text.
     */
    private static Path resolveFolders(Path folder) {
        Path existing = folder;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return folder.normalize();
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(folder)).normalize();
        } catch (IOException | IllegalArgumentException _) {
            return folder.normalize();
        }
    }

    /** The form in which two paths are compared: in lower case where file names ignore case. */
    static Path compared(Path path) {
        return HostOs.NAMES_IGNORE_CASE ? Path.of(path.toString().toLowerCase(Locale.ROOT)) : path;
    }
}
