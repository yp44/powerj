package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void invalidHistorySizeFallsBackToDefault() throws Exception {
        var file = Files.writeString(tmp.resolve("config.properties"), "history.size=-3\n");

        assertThat(ShellConfig.load(file)).isEqualTo(ShellConfig.defaults());
    }
}
