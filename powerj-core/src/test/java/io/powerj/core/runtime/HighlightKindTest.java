package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.fs.Host;

/**
 * The colour of a word being typed does not look at the file system at every keystroke (audit v2, S-08, #176): a bare word is
 * answered from the names of the {@code PATH} found by one scan, kept for a short time.
 */
class HighlightKindTest {

    @TempDir
    Path tmp;

    @Test
    void aBareWordIsAnsweredFromTheCachedNamesNotFromTheFileSystem() throws Exception {
        assumeFalse(Host.windows(), "a Unix program");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path tool = bin.resolve("mytool");
        Files.writeString(tool, "#!/bin/sh\n");
        assertThat(tool.toFile().setExecutable(true)).isTrue();
        Map<String, String> environment = new HashMap<>(Map.of("PATH", bin.toString()));
        var session = new Session(tmp, tmp, environment);
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of()));

        assertThat(interpreter.commandKind("mytool")).isEqualTo(CommandKind.NATIVE);
        assertThat(interpreter.commandKind("nothere")).isEqualTo(CommandKind.UNKNOWN);

        Files.delete(tool); // the cache still has the name: no lookup of the file system for the keystroke
        assertThat(interpreter.commandKind("mytool")).as("answered from the cache").isEqualTo(CommandKind.NATIVE);
        // what runs is still decided on the file system
        assertThat(interpreter.resolve("mytool")).isInstanceOf(Resolved.Unknown.class);
    }

    @Test
    void aBuiltinAndAForcedProgramAreStillKnown() {
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of()));

        assertThat(interpreter.commandKind("cd")).isEqualTo(CommandKind.BUILTIN);
        assertThat(interpreter.commandKind("^cd")).isEqualTo(CommandKind.UNKNOWN);
    }
}
