package io.powerj.core.nativeexec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.runtime.Session;

class CommandResolverTest {

    @TempDir
    Path tmp;

    @Test
    void unixSearchesPathForExecutableFiles() throws Exception {
        assumeFalse(Platform.isWindows(), "permissions POSIX indisponibles sous Windows");
        var bin = Files.createDirectories(tmp.resolve("bin"));
        var tool = Files.createFile(bin.resolve("outil"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        Files.createFile(bin.resolve("pas-executable"));
        var session = new Session(tmp, tmp, Map.of("PATH", "/nulle/part:" + bin));
        var resolver = new CommandResolver(false);

        assertThat(resolver.resolve("outil", session.currentDirectory(), session.environment())).contains(tool);
        assertThat(resolver.resolve("pas-executable", session.currentDirectory(), session.environment())).isEmpty();
        assertThat(resolver.resolve("absent", session.currentDirectory(), session.environment())).isEmpty();
        assertThat(resolver.resolve("bin/outil", session.currentDirectory(), session.environment())).contains(tool);
        assertThat(resolver.resolve("./bin/outil", session.currentDirectory(), session.environment())).contains(tool);
    }

    @Test
    void windowsTriesPathextExtensions() throws Exception {
        var bin = Files.createDirectories(tmp.resolve("bin"));
        var exe = Files.createFile(bin.resolve("git.exe"));
        var cmd = Files.createFile(bin.resolve("mvn.cmd"));
        var session = new Session(tmp, tmp, Map.of("PATH", bin.toString(), "PATHEXT", ".COM;.EXE;.BAT;.CMD"));
        var resolver = new CommandResolver(true);

        assertThat(resolver.resolve("git", session.currentDirectory(), session.environment())).contains(exe);
        assertThat(resolver.resolve("git.exe", session.currentDirectory(), session.environment())).contains(exe);
        assertThat(resolver.resolve("mvn", session.currentDirectory(), session.environment())).contains(cmd);
        assertThat(resolver.resolve("absent", session.currentDirectory(), session.environment())).isEmpty();
    }
}
