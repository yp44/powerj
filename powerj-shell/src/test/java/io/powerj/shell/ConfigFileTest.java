package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileTest {

    @TempDir
    Path tmp;

    private ConfigFile config() {
        return new ConfigFile(tmp.resolve("config.properties"));
    }

    @Test
    void aMissingFileHasNoValue() throws IOException {
        assertThat(config().get("language")).isEmpty();
    }

    @Test
    void readsAValue() throws IOException {
        Files.writeString(tmp.resolve("config.properties"), "language=fr\nmodules.nativeAccess.io.powerj.fx=true\n");
        assertThat(config().get("language")).contains("fr");
        assertThat(config().get("modules.nativeAccess.io.powerj.fx")).contains("true");
        assertThat(config().get("history.size")).isEmpty();
    }

    @Test
    void aMalformedFileIsAnIoErrorNotARuntimeException() throws IOException {
        Files.writeString(tmp.resolve("config.properties"), "x=C:\\users\\yves\n");
        assertThatThrownBy(() -> config().get("x")).isInstanceOf(IOException.class).hasMessageContaining("Malformed");
    }

    @Test
    void anAnswerCanStillBeRecordedInAMalformedFile() throws IOException {
        Path file = tmp.resolve("config.properties");
        Files.writeString(file, "x=C:\\users\\yves\n");
        config().set("modules.nativeAccess.org.a", "true");
        assertThat(Files.readAllLines(file)).containsExactly("x=C:\\users\\yves", "modules.nativeAccess.org.a=true");
    }

    @Test
    void creatingTheFileCreatesItsFolderToo() throws IOException {
        var file = new ConfigFile(tmp.resolve("home").resolve("config.properties"));
        file.set("a", "1");
        assertThat(Files.readString(tmp.resolve("home").resolve("config.properties"))).isEqualTo("a=1" + System.lineSeparator());
        assertThat(file.path()).isEqualTo(tmp.resolve("home").resolve("config.properties"));
    }

    @Test
    void changingAKeyKeepsCommentsOrderAndOtherKeys() throws IOException {
        Path file = tmp.resolve("config.properties");
        Files.write(file, java.util.List.of("# my settings", "language = fr", "", "! other", "history.size=500",
                "modules.nativeAccess.org.a=false"), StandardCharsets.UTF_8);
        config().set("modules.nativeAccess.org.a", "true");
        assertThat(Files.readAllLines(file)).containsExactly("# my settings", "language = fr", "", "! other",
                "history.size=500", "modules.nativeAccess.org.a=true");
    }

    @Test
    void anotherKeyIsAddedAtTheEnd() throws IOException {
        Path file = tmp.resolve("config.properties");
        Files.write(file, java.util.List.of("language=fr"), StandardCharsets.UTF_8);
        config().set("modules.nativeAccess.org.a", "false");
        config().set("modules.nativeAccess.org.b", "true");
        assertThat(Files.readAllLines(file)).containsExactly("language=fr", "modules.nativeAccess.org.a=false",
                "modules.nativeAccess.org.b=true");
        assertThat(config().get("modules.nativeAccess.org.b")).contains("true");
    }

    @Test
    void aKeyThatContainsAnotherIsNotTouched() throws IOException {
        Path file = tmp.resolve("config.properties");
        Files.write(file, java.util.List.of("modules.nativeAccess.org.a.more=1"), StandardCharsets.UTF_8);
        config().set("modules.nativeAccess.org.a", "true");
        assertThat(Files.readAllLines(file)).containsExactly("modules.nativeAccess.org.a.more=1",
                "modules.nativeAccess.org.a=true");
    }

    @Test
    void everyLineOfARepeatedKeyIsChanged() throws IOException {
        Path file = tmp.resolve("config.properties");
        Files.write(file, java.util.List.of("a=1", "b=2", "a=3"), StandardCharsets.UTF_8);
        config().set("a", "9");
        assertThat(Files.readAllLines(file)).containsExactly("a=9", "b=2", "a=9");
    }

    @Test
    void noTemporaryFileIsLeftBehind() throws IOException {
        config().set("a", "1");
        try (var files = Files.list(tmp)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("config.properties");
        }
    }

    @Test
    void anUnwritableTargetIsAnError() throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("config.properties").resolve("sub"));
        assertThat(folder).isDirectory();
        assertThatThrownBy(() -> config().set("a", "1")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> config().get("a")).isInstanceOf(IOException.class);
    }

    @Test
    void keyOfReadsTheKeyOfALine() {
        assertThat(ConfigFile.keyOf("a=1")).isEqualTo("a");
        assertThat(ConfigFile.keyOf("  a = 1")).isEqualTo("a");
        assertThat(ConfigFile.keyOf("a:1")).isEqualTo("a");
        assertThat(ConfigFile.keyOf("a 1")).isEqualTo("a");
        assertThat(ConfigFile.keyOf("a")).isEqualTo("a");
        assertThat(ConfigFile.keyOf("a\\ b=1")).isEqualTo("a b");
        assertThat(ConfigFile.keyOf("a\\")).isEqualTo("a\\"); // a lone backslash at the end stays
        assertThat(ConfigFile.keyOf("# a=1")).isNull();
        assertThat(ConfigFile.keyOf("! a=1")).isNull();
        assertThat(ConfigFile.keyOf("   ")).isNull();
        assertThat(ConfigFile.keyOf("")).isNull();
    }
}
