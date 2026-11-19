package io.powerj.cmdlets;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

import io.powerj.api.Bytes;
import io.powerj.api.Display;

/**
 * File or directory listed by {@code ls} (specification FR-35).
 *
 * @param name     name with extension
 * @param size     size in bytes (0 for a directory)
 * @param modified last modification date
 * @param path     absolute path (not displayed by default: {@code (ls)*.path}, {@code f.path})
 * @param dir      {@code true} for a directory
 * @param ext      extension without the dot, {@code ""} if none
 * @param link     {@code true} for a symbolic link: the other attributes are those of its target (a link to a folder
 *                 has {@code dir} true), or the link's own when it is broken; not displayed by default
 * @param hidden   {@code true} for a hidden entry (a name that starts with a dot, or the hidden attribute on Windows);
 *                 not displayed by default
 */
@Display(columns = {"name", "size", "modified", "dir"})
public record FileEntry(String name, @Bytes long size, Instant modified, Path path, boolean dir, String ext,
                        boolean link, boolean hidden) {

    public FileEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(modified, "modified");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(ext, "ext");
    }

    /** An entry that is neither a link nor hidden. */
    public FileEntry(String name, long size, Instant modified, Path path, boolean dir, String ext) {
        this(name, size, modified, path, dir, ext, false, false);
    }

    /** Extension of a file name, without the dot; {@code ""} for a directory or a name without an extension. */
    static String extensionOf(String name, boolean dir) {
        int dot = name.lastIndexOf('.');
        return dir || dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }
}
