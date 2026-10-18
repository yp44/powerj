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
