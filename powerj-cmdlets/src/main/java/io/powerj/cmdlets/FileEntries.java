package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/** {@link FileEntry} of a path, as listed by {@code ls} (shared with the file operations). */
final class FileEntries {

    private FileEntries() {
    }

    /**
     * Entry of an existing path, or empty if it cannot be read. With {@link LinkOption#NOFOLLOW_LINKS}, a link is
     * described itself (file operations); otherwise its target is (as {@code ls} does).
     */
    static Optional<FileEntry> of(Path path, LinkOption... options) {
        try {
            return Optional.of(of(path, Files.readAttributes(path, BasicFileAttributes.class, options)));
        } catch (IOException _) {
            return Optional.empty(); // vanished between listing and reading
        }
    }

    static FileEntry of(Path path, BasicFileAttributes attributes) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        boolean dir = attributes.isDirectory();
        return new FileEntry(name, dir ? 0 : attributes.size(), attributes.lastModifiedTime().toInstant(),
                path.toAbsolutePath(), dir, FileEntry.extensionOf(name, dir));
    }
}
