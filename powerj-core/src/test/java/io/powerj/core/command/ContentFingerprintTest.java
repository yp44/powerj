package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a consent to native access is given for: the content of the modules that would be loaded, not their name. */
class ContentFingerprintTest {

    @TempDir
    Path tmp;

    private Path file(String name, String content) throws IOException {
        Path path = tmp.resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static String of(Map<String, Path> modules) throws IOException {
        return ContentFingerprint.of(modules);
    }

    @Test
    void aFingerprintIsSixtyFourLowerCaseHexDigits() throws IOException {
        assertThat(of(Map.of("a", file("a.jar", "x")))).matches("[0-9a-f]{64}");
    }

    @Test
    void theSameContentGivesTheSameFingerprintWhereverItIs() throws IOException {
        assertThat(of(Map.of("a", file("one/a.jar", "same")))).isEqualTo(of(Map.of("a", file("two/a.jar", "same"))));
    }

    @Test
    void anotherContentGivesAnotherFingerprint() throws IOException {
        assertThat(of(Map.of("a", file("one/a.jar", "before")))).isNotEqualTo(of(Map.of("a", file("two/a.jar", "after"))));
    }

    @Test
    void theNameOfTheModuleIsPartOfIt() throws IOException {
        Path jar = file("a.jar", "same");

        assertThat(of(Map.of("org.a", jar))).isNotEqualTo(of(Map.of("org.b", jar)));
    }

    @Test
    void theOrderOfTheModulesDoesNotMatter() throws IOException {
        Path first = file("x.jar", "1");
        Path second = file("y.jar", "2");
        var forward = new LinkedHashMap<String, Path>();
        forward.put("x", first);
        forward.put("y", second);
        var backward = new LinkedHashMap<String, Path>();
        backward.put("y", second);
        backward.put("x", first);

        assertThat(of(forward)).isEqualTo(of(backward));
    }

    @Test
    void everyModuleOfTheLayerCounts() throws IOException {
        Path root = file("root.jar", "root");

        String alone = of(Map.of("root", root));
        String withDependency = of(Map.of("root", root, "dep", file("dep.jar", "dep")));
        String withAnotherDependency = of(Map.of("root", root, "dep", file("dep2.jar", "changed")));

        assertThat(withDependency).isNotEqualTo(alone);
        assertThat(withAnotherDependency).isNotEqualTo(withDependency);
    }

    @Test
    void contentCannotBeMovedFromOneModuleToAnother() throws IOException {
        Path one = file("one.jar", "1");
        Path two = file("two.jar", "2");

        assertThat(of(Map.of("x", one, "y", two))).isNotEqualTo(of(Map.of("x", two, "y", one)));
    }

    @Test
    void aFolderCountsByTheNamesAndTheContentOfItsFiles() throws IOException {
        file("exploded/module-info.class", "m");
        file("exploded/pkg/A.class", "a");
        String before = of(Map.of("m", tmp.resolve("exploded")));

        file("exploded/pkg/A.class", "changed");
        String changed = of(Map.of("m", tmp.resolve("exploded")));
        file("exploded/pkg/A.class", "a");
        Files.move(tmp.resolve("exploded/pkg/A.class"), tmp.resolve("exploded/pkg/B.class"));
        String renamed = of(Map.of("m", tmp.resolve("exploded")));

        assertThat(changed).isNotEqualTo(before);
        assertThat(renamed).isNotEqualTo(before).isNotEqualTo(changed);
    }

    @Test
    void aFolderGivesTheSameFingerprintWhateverTheOrderOfItsFiles() throws IOException {
        file("one/a.class", "1");
        file("one/b.class", "2");
        file("two/b.class", "2");
        file("two/a.class", "1");

        assertThat(of(Map.of("m", tmp.resolve("one")))).isEqualTo(of(Map.of("m", tmp.resolve("two"))));
    }

    @Test
    void aMissingFileCannotBeAnswered() {
        assertThatThrownBy(() -> of(Map.of("a", tmp.resolve("gone.jar")))).isInstanceOf(IOException.class);
    }
}
