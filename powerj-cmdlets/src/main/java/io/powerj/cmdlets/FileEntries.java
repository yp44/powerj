package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.Optional;

import io.powerj.fs.FollowedLink;

/** {@link FileEntry} of a path, as listed by {@code ls} (shared with the file operations). */
final class FileEntries {

    private FileEntries() {
    }

    /**
     * Entry of an existing path, or empty if it cannot be read. With {@link LinkOption#NOFOLLOW_LINKS}, a link is
     * described itself (file operations); otherwise its target is, as {@code ls} does, and a link that is broken is
     * described itself too: it is there, and {@link FileEntry#link()} says what it is.
     */
    static Optional<FileEntry> of(Path path, LinkOption... options) {
        try {
            BasicFileAttributes own = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!own.isSymbolicLink() || options.length > 0) {
                return Optional.of(of(path, own));
            }
            try {
                return Optional.of(of(path, FollowedLink.of(Files.readAttributes(path, BasicFileAttributes.class))));
            } catch (IOException _) {
                return Optional.of(of(path, own)); // a broken link
            }
        } catch (IOException _) {
            return Optional.empty(); // vanished between listing and reading
        }
    }

    static FileEntry of(Path path, BasicFileAttributes attributes) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        boolean dir = attributes.isDirectory();
        return new FileEntry(name, dir ? 0 : attributes.size(), attributes.lastModifiedTime().toInstant(),
                path.toAbsolutePath(), dir, FileEntry.extensionOf(name, dir), attributes.isSymbolicLink(),
                hidden(name, attributes));
    }

    /** The hidden attribute on Windows, a leading dot elsewhere (what {@code Files.isHidden} says, without a system call). */
    private static boolean hidden(String name, BasicFileAttributes attributes) {
        return attributes instanceof DosFileAttributes dos ? dos.isHidden() : name.startsWith(".");
    }
}
