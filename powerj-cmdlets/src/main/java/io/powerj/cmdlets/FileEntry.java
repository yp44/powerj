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
 */
@Display(columns = {"name", "size", "modified", "dir"})
public record FileEntry(String name, @Bytes long size, Instant modified, Path path, boolean dir, String ext) {

    public FileEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(modified, "modified");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(ext, "ext");
    }

    /** Extension of a file name, without the dot; {@code ""} for a directory or a name without an extension. */
    static String extensionOf(String name, boolean dir) {
        int dot = name.lastIndexOf('.');
        return dir || dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }
}
