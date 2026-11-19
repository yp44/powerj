package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;

class FollowedLinkTest {

    private record Plain(boolean link) implements BasicFileAttributes {
        public FileTime lastModifiedTime() {
            return FileTime.fromMillis(1);
        }

        public FileTime lastAccessTime() {
            return FileTime.fromMillis(2);
        }

        public FileTime creationTime() {
            return FileTime.fromMillis(3);
        }

        public boolean isRegularFile() {
            return true;
        }

        public boolean isDirectory() {
            return false;
        }

        public boolean isSymbolicLink() {
            return link;
        }

        public boolean isOther() {
            return false;
        }

        public long size() {
            return 42;
        }

        public Object fileKey() {
            return null;
        }
    }

    private record Dos(boolean hidden) implements DosFileAttributes {
        public boolean isReadOnly() {
            return true;
        }

        public boolean isHidden() {
            return hidden;
        }

        public boolean isArchive() {
            return false;
        }

        public boolean isSystem() {
            return false;
        }

        public FileTime lastModifiedTime() {
            return FileTime.fromMillis(1);
        }

        public FileTime lastAccessTime() {
            return FileTime.fromMillis(2);
        }

        public FileTime creationTime() {
            return FileTime.fromMillis(3);
        }

        public boolean isRegularFile() {
            return true;
        }

        public boolean isDirectory() {
            return false;
        }

        public boolean isSymbolicLink() {
            return false;
        }

        public boolean isOther() {
            return false;
        }

        public long size() {
            return 7;
        }

        public Object fileKey() {
            return null;
        }
    }

    @Test
    void aFollowedLinkIsALinkWithTheAttributesOfItsTarget() {
        BasicFileAttributes followed = FollowedLink.of(new Plain(false));

        assertThat(followed.isSymbolicLink()).isTrue();
        assertThat(followed.size()).isEqualTo(42);
        assertThat(followed).isNotInstanceOf(DosFileAttributes.class);
    }

    @Test
    void aLinkToATargetWithDosAttributesKeepsThemSoThatAHiddenTargetIsHidden() {
        BasicFileAttributes followed = FollowedLink.of(new Dos(true));

        assertThat(followed.isSymbolicLink()).isTrue();
        assertThat(followed).isInstanceOfSatisfying(DosFileAttributes.class, dos -> {
            assertThat(dos.isHidden()).isTrue();
            assertThat(dos.isReadOnly()).isTrue();
        });
        assertThat(((DosFileAttributes) FollowedLink.of(new Dos(false))).isHidden()).isFalse();
    }

    @Test
    void attributesThatAreAlreadyALinkAreKept() {
        var own = new Plain(true);

        assertThat(FollowedLink.of(own)).isSameAs(own);
    }
}
