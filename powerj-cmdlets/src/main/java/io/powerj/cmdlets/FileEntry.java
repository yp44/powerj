package io.powerj.cmdlets;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

import io.powerj.api.Bytes;
import io.powerj.api.Display;

/**
 * Fichier ou dossier listé par {@code ls} (spécification FR-35).
 *
 * @param name     nom avec extension
 * @param size     taille en octets (0 pour un dossier)
 * @param modified date de dernière modification
 * @param path     chemin absolu
 * @param dir      {@code true} pour un dossier
 * @param ext      extension sans le point, {@code ""} si aucune
 */
@Display(columns = {"name", "size", "modified", "dir", "path"})
public record FileEntry(String name, @Bytes long size, Instant modified, Path path, boolean dir, String ext) {

    public FileEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(modified, "modified");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(ext, "ext");
    }

    /** Extension d'un nom de fichier, sans le point ; {@code ""} pour un dossier ou un nom sans extension. */
    static String extensionOf(String name, boolean dir) {
        int dot = name.lastIndexOf('.');
        return dir || dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }
}
