package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    // --- what is written reads back as it was (#93) ---

    /** A value with a backslash used to make the file unreadable ({@code Malformed \\uxxxx}), a line break to split it. */
    @ParameterizedTest
    @ValueSource(strings = {"plain", "", "C:\\users\\yves", "ends with a backslash\\", "\\u0041 is not an A",
            "line one\nline two", "carriage\rreturn", "a=b", "a:b", " leading space", "trailing space ", "\ttab first",
            "form\ffeed", "#not a comment", "!not a comment", "accents é à ü ñ, 日本語", "emoji \uD83D\uDE00",
            "lone surrogate \uD800 inside", "control \u0001 character"})
    void aValueIsReadBackAsItWasWritten(String value) throws IOException {
        config().set("setting", value);

        assertThat(config().get("setting")).contains(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain.key", "with space", "equals=sign", "colon:sign", "#hash", "!bang", "back\\slash",
            "tab\tkey", "é accent", "emoji \uD83D\uDE00", "control \u0001 key"})
    void aKeyIsReadBackAsItWasWritten(String key) throws IOException {
        config().set(key, "value");

        assertThat(config().get(key)).contains("value");
        assertThat(config().read()).containsOnlyKeys(key);
    }

    @Test
    void aValueWithLineBreaksStaysOnOneLine() throws IOException {
        config().set("setting", "one\ntwo\r\nthree");

        assertThat(Files.readAllLines(tmp.resolve("config.properties"))).hasSize(1);
    }

    @Test
    void aKeyThatNeedsEscapingIsReplacedNotRepeated() throws IOException {
        config().set("a key=with:signs", "1");
        config().set("a key=with:signs", "2");
        config().set("other", "3");

        assertThat(Files.readAllLines(tmp.resolve("config.properties"))).hasSize(2);
        assertThat(config().get("a key=with:signs")).contains("2");
        assertThat(config().get("other")).contains("3");
    }

    /** The original failure (review finding C-11): one setting with a Windows path made every other one unreadable. */
    @Test
    void aWindowsPathDoesNotMakeTheOtherSettingsUnreadable() throws IOException {
        Files.write(tmp.resolve("config.properties"), java.util.List.of("language=fr", "# mine"), StandardCharsets.UTF_8);

        config().set("modules.path", "C:\\Program Files\\PowerJ\\modules");

        assertThat(config().read()).containsEntry("language", "fr")
                .containsEntry("modules.path", "C:\\Program Files\\PowerJ\\modules");
        assertThat(ShellConfig.load(tmp.resolve("config.properties")).language()).isEqualTo("fr");
    }

    /** What the shell writes today (a consent for a module) is written exactly as before: an existing file keeps its lines. */
    @Test
    void theSettingsTheShellWritesAreWrittenAsBefore() throws IOException {
        String fingerprint = "sha256:0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0";

        config().set("modules.nativeAccess.io.powerj.fx", fingerprint);
        config().set("modules.nativeAccess.com.example.greet", "false");
        config().set("language", "fr");

        assertThat(Files.readAllLines(tmp.resolve("config.properties"))).containsExactly(
                "modules.nativeAccess.io.powerj.fx=" + fingerprint, "modules.nativeAccess.com.example.greet=false",
                "language=fr");
    }

    @Test
    void keyOfReadsTheEscapesOfAKey() {
        assertThat(ConfigFile.keyOf("a\\tb=1")).isEqualTo("a\tb");
        assertThat(ConfigFile.keyOf("a\\nb=1")).isEqualTo("a\nb");
        assertThat(ConfigFile.keyOf("\\u0041\\u00e9=1")).isEqualTo("A\u00e9");
        assertThat(ConfigFile.keyOf("a\\=b=1")).isEqualTo("a=b");
        assertThat(ConfigFile.keyOf("a\\u00zz=1")).isEqualTo("au00zz"); // not an escape: as before, the letter stays
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

    /** Two settings recorded at the same time (two consents, two instances) must both be there afterwards. */
    @Test
    void settingsRecordedAtTheSameTimeAreAllKept() throws Exception {
        int writers = 12;
        var barrier = new java.util.concurrent.CyclicBarrier(writers);
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < writers; i++) {
            int n = i;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    var own = config(); // an instance of its own, as two parts of the shell have
                    barrier.await();
                    own.set("key" + n, "value" + n);
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failures).isEmpty();
        for (int i = 0; i < writers; i++) {
            assertThat(config().get("key" + i)).as("key" + i).contains("value" + i);
        }
    }

    @Test
    void theWholeFileCanBeRead() throws IOException {
        Files.writeString(tmp.resolve("config.properties"), "a=1\nb = two\n");

        assertThat(config().read()).containsEntry("a", "1").containsEntry("b", "two").hasSize(2);
        assertThat(new ConfigFile(tmp.resolve("absent")).read()).isEmpty();
    }
}
