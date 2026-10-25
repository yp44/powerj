package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtectedPathsTest {

    @TempDir
    Path tmp;

    private ProtectedPaths load(String... lines) throws IOException {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Files.write(config.resolve(ProtectedPaths.FILE_NAME), List.of(lines));
        return ProtectedPaths.load(Optional.of(config), Map.of("HOME", tmp.resolve("home").toString()),
                tmp.resolve("work"));
    }

    @Test
    void readsTheFileWithCommentsAndHome() throws IOException {
        var paths = load("# comment", "", "  " + tmp.resolve("keep") + "  ", "~/docs");
        assertThat(paths.refusal(tmp.resolve("keep"))).get().asString().startsWith("protected path:");
        assertThat(paths.refusal(tmp.resolve("home/docs"))).isPresent();
        assertThat(paths.refusal(tmp.resolve("home"))).get().asString().contains("contains the protected path");
        assertThat(paths.refusal(tmp.resolve("keep/inside.txt"))).isEmpty(); // inside a protected folder: allowed
        assertThat(paths.refusal(tmp.resolve("other"))).isEmpty();
    }

    @Test
    void aFolderContainingAProtectedPathIsRefused() throws IOException {
        var paths = load(tmp.resolve("a/b/c").toString());
        assertThat(paths.refusal(tmp.resolve("a"))).isPresent();
        assertThat(paths.refusal(tmp.resolve("a/b/../b"))).isPresent(); // normalized
        assertThat(paths.refusal(tmp.resolve("a/x"))).isEmpty();
    }

    @Test
    void theCurrentFolderAndItsParentsAreRefused() throws IOException {
        var paths = load();
        assertThat(paths.refusal(tmp.resolve("work"))).get().asString().contains("current folder");
        assertThat(paths.refusal(tmp)).isPresent();
        assertThat(paths.refusal(tmp.resolve("work/sub"))).isEmpty();
    }

    // --- Links (review C-21): a path is compared as the file system sees it ---

    private boolean symbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    @Test
    void anAliasOfAProtectedFolderIsRefusedAsTheFolderIs() throws IOException {
        Path keep = Files.createDirectories(tmp.resolve("keep"));
        Files.createDirectories(keep.resolve("sub"));
        org.junit.jupiter.api.Assumptions.assumeTrue(symbolicLink(tmp.resolve("alias"), keep), "symbolic links not available");
        var paths = load(keep.toString());

        assertThat(paths.refusal(tmp.resolve("alias/sub"))).isEmpty(); // inside a protected folder: allowed
        assertThat(paths.refusal(keep)).isPresent();
    }

    @Test
    void dotDotAfterALinkIsTheParentOfWhatTheLinkPointsTo() throws IOException {
        Path protectedFolder = Files.createDirectories(tmp.resolve("area/p"));
        Files.createDirectories(protectedFolder.resolve("sub"));
        Files.createDirectories(tmp.resolve("area/q"));
        org.junit.jupiter.api.Assumptions.assumeTrue(symbolicLink(tmp.resolve("area/q/alias"), protectedFolder.resolve("sub")),
                "symbolic links not available");
        var paths = load(protectedFolder.toString());

        // Written out, `area/q/alias/..` looks like `area/q`; the file system goes up from `area/p/sub`, to the folder
        // that is protected. Normalizing the text, as Path.normalize does, gets it wrong.
        assertThat(paths.refusal(tmp.resolve("area/q/alias/.."))).get().asString().startsWith("protected path:");
        assertThat(paths.refusal(tmp.resolve("area/q"))).isEmpty();
    }

    @Test
    void aLinkToAFolderThatContainsAProtectedPathIsRefusedWhenItIsTraversed() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Files.createDirectories(home.resolve("user/docs"));
        org.junit.jupiter.api.Assumptions.assumeTrue(symbolicLink(tmp.resolve("alias"), home), "symbolic links not available");
        var paths = load(home.resolve("user").toString());

        // `alias/user` is `home/user`, the protected folder itself
        assertThat(paths.refusal(tmp.resolve("alias/user"))).get().asString().startsWith("protected path:");
        assertThat(paths.refusal(tmp.resolve("alias/user/docs"))).isEmpty();
    }

    @Test
    void theLinkItselfMayBeRemoved() throws IOException {
        Path keep = Files.createDirectories(tmp.resolve("keep"));
        org.junit.jupiter.api.Assumptions.assumeTrue(symbolicLink(tmp.resolve("alias"), keep), "symbolic links not available");
        var paths = load(keep.toString());

        // rm removes the link, not the folder it points to
        assertThat(paths.refusal(tmp.resolve("alias"))).isEmpty();
    }

    @Test
    void aProtectedEntryThatIsALinkProtectsWhatItPointsTo() throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real"));
        org.junit.jupiter.api.Assumptions.assumeTrue(symbolicLink(tmp.resolve("etc"), real), "symbolic links not available");
        var paths = load(tmp.resolve("etc").toString());

        assertThat(paths.refusal(real)).get().asString().startsWith("protected path:");
        assertThat(paths.refusal(tmp.resolve("etc"))).get().asString().startsWith("protected path:");
    }

    @Test
    void beingInALinkToAFolderIsBeingInIt() throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real/work"));
        org.junit.jupiter.api.Assumptions.assumeTrue(symbolicLink(tmp.resolve("shortcut"), real), "symbolic links not available");
        var paths = ProtectedPaths.load(Optional.empty(), Map.of("HOME", tmp.resolve("home").toString()),
                tmp.resolve("shortcut"));

        assertThat(paths.refusal(real)).get().asString().contains("current folder");
        assertThat(paths.refusal(tmp.resolve("real"))).get().asString().contains("current folder");
    }

    @Test
    void aMissingFileGivesTheDefaults() {
        var paths = ProtectedPaths.load(Optional.of(tmp.resolve("absent")), Map.of("HOME", tmp.resolve("home").toString()),
                tmp.resolve("work"));
        Path root = tmp.getRoot();
        assertThat(paths.refusal(root)).isPresent();
        assertThat(paths.refusal(tmp.resolve("home"))).isPresent();
        var none = ProtectedPaths.load(Optional.empty(), Map.of(), tmp.resolve("work"));
        assertThat(none.refusal(root)).isPresent();
    }

    @Test
    void defaultFileListsTheRootsHomeAndConfigFolder() {
        List<String> lines = ProtectedPaths.defaultFile(tmp.resolve("config"));
        assertThat(lines.getFirst()).startsWith("#");
        assertThat(lines).contains("~", tmp.resolve("config").toString(), tmp.getRoot().toString());
    }
}
