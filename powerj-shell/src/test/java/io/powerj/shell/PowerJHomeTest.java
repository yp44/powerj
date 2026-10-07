package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerJHomeTest {

    @TempDir
    Path tmp;

    @Test
    void defaultsToDotPowerjInUserHome() {
        var home = PowerJHome.resolve(Map.of(), tmp);

        assertThat(home.dir()).isEqualTo(tmp.resolve(".powerj"));
        assertThat(home.historyFile()).isEqualTo(tmp.resolve(".powerj/history"));
        assertThat(home.configFile()).isEqualTo(tmp.resolve(".powerj/config.properties"));
    }

    @Test
    void powerjHomeVariableWins() {
        var home = PowerJHome.resolve(Map.of("POWERJ_HOME", tmp.resolve("ailleurs").toString()), tmp);

        assertThat(home.dir()).isEqualTo(tmp.resolve("ailleurs"));
    }

    @Test
    void createDirectoriesCreatesLogsDir() throws Exception {
        var home = new PowerJHome(tmp.resolve("h")).createDirectories();

        assertThat(Files.isDirectory(home.logsDir())).isTrue();
    }
}
