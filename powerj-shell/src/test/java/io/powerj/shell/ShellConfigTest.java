package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Language;

class ShellConfigTest {

    @TempDir
    Path tmp;

    @Test
    void missingFileGivesDefaults() {
        assertThat(ShellConfig.load(tmp.resolve("absent.properties"))).isEqualTo(ShellConfig.defaults());
        assertThat(ShellConfig.defaults().historySize()).isEqualTo(10_000);
    }

    @Test
    void historySizeIsRead() throws Exception {
        var file = Files.writeString(tmp.resolve("config.properties"), "history.size = 500\n");

        assertThat(ShellConfig.load(file).historySize()).isEqualTo(500);
    }

    @Test
    void languageIsRead() throws Exception {
        var file = Files.writeString(tmp.resolve("config.properties"), "history.size=500\nlanguage = fr\n");

        assertThat(ShellConfig.load(file).language()).isEqualTo("fr");
    }

    @Test
    void absentLanguageIsNull() throws Exception {
        var file = Files.writeString(tmp.resolve("config.properties"), "history.size=500\n");

        assertThat(ShellConfig.load(file).language()).isNull();
        assertThat(ShellConfig.load(tmp.resolve("absent.properties")).language()).isNull();
        assertThat(ShellConfig.defaults().language()).isNull();
    }

    @Test
    void nonPositiveHistorySizeIsRejected() {
        assertThatThrownBy(() -> new ShellConfig(0, null)).hasMessage("history.size must be positive: 0");
        Language.set(Locale.FRENCH);
        try {
            assertThatThrownBy(() -> new ShellConfig(-1, null)).hasMessage("history.size doit être positif : -1");
        } finally {
            Language.set(Locale.ENGLISH);
        }
    }

    @Test
    void invalidHistorySizeFallsBackToDefault() throws Exception {
        var file = Files.writeString(tmp.resolve("config.properties"), "history.size=-3\n");

        assertThat(ShellConfig.load(file)).isEqualTo(ShellConfig.defaults());
    }

    /**
     * Review C-11: {@code Properties.load} throws IllegalArgumentException on a malformed {@code \\uXXXX}, which a Windows path
     * written without doubling its backslashes is. The class promises that invalid values take their default.
     */
    @Disabled("C-11, fixed in Phase 1.2: ShellConfig.load, ConfigFile and NativeAccessConsent must survive a malformed file")
    @Test
    void aWindowsPathWithSingleBackslashesDoesNotPreventStartup() throws Exception {
        var file = Files.writeString(tmp.resolve("config.properties"), "history.size=500\nsomething=C:\\users\\yves\n");

        assertThat(ShellConfig.load(file)).isEqualTo(ShellConfig.defaults());
    }
}
