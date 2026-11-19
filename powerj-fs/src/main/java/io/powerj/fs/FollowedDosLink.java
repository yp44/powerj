package io.powerj.fs;

import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * {@link FollowedLink} for a target that has the DOS attributes (Windows): the hidden, read-only, archive and system flags
 * are those of the target, so a link to a hidden file is hidden for a listing.
 */
public record FollowedDosLink(DosFileAttributes target) implements DosFileAttributes {

    @Override
    public boolean isReadOnly() {
        return target.isReadOnly();
    }

    @Override
    public boolean isHidden() {
        return target.isHidden();
    }

    @Override
    public boolean isArchive() {
        return target.isArchive();
    }

    @Override
    public boolean isSystem() {
        return target.isSystem();
    }

    @Override
    public FileTime lastModifiedTime() {
        return target.lastModifiedTime();
    }

    @Override
    public FileTime lastAccessTime() {
        return target.lastAccessTime();
    }

    @Override
    public FileTime creationTime() {
        return target.creationTime();
    }

    @Override
    public boolean isRegularFile() {
        return target.isRegularFile();
    }

    @Override
    public boolean isDirectory() {
        return target.isDirectory();
    }

    @Override
    public boolean isSymbolicLink() {
        return true;
    }

    @Override
    public boolean isOther() {
        return target.isOther();
    }

    @Override
    public long size() {
        return target.size();
    }

    @Override
    public Object fileKey() {
        return target.fileKey();
    }
}
