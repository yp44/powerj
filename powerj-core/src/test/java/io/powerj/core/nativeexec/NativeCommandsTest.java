package io.powerj.core.nativeexec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The names of the programs of the {@code PATH}, as the highlighting asks for them (audit v2, S-08; #176). */
class NativeCommandsTest {

    @TempDir
    Path tmp;

    /** The rules of Windows, checked on any system: a name is found without its extension, with it, in any case. */
    @Test
    void onWindowsAProgramIsFoundWithOrWithoutItsExtensionAndInAnyCase() throws Exception {
        Files.writeString(tmp.resolve("git.exe"), "");
        Files.writeString(tmp.resolve("notes.txt"), "");
        var commands = new NativeCommands(true);
        Map<String, String> environment = Map.of("PATH", tmp.toString(), "PATHEXT", ".COM;.EXE;.BAT;.CMD");

        assertThat(commands.has(environment, "git")).isTrue();
        assertThat(commands.has(environment, "git.exe")).isTrue();
        assertThat(commands.has(environment, "GIT.EXE")).isTrue();
        assertThat(commands.has(environment, "git.bat")).as("another extension of PATHEXT, no such file").isFalse();
        assertThat(commands.has(environment, "notes")).as("not an extension of PATHEXT").isFalse();
        assertThat(commands.has(environment, "notes.txt")).isFalse();
    }
}
