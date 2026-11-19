package io.powerj.fs;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * The attributes of what a symbolic link points to, that still say the entry is a link: a listing shows the target (a link
 * to a folder is a folder) and must not lose that it is a link, or a user cannot tell it from the folder before
 * {@code rm -r} (audit v2, U-06). Everything but {@link #isSymbolicLink()} is the target's.
 */
public record FollowedLink(BasicFileAttributes target) implements BasicFileAttributes {

    /** The attributes of a link that was followed. */
    public static BasicFileAttributes of(BasicFileAttributes target) {
        return target.isSymbolicLink() ? target : new FollowedLink(target);
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
