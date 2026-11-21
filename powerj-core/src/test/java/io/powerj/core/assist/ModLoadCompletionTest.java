package io.powerj.core.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/** Tab completion of {@code mod-load} (#241): a module is a folder or a jar, so those are offered, and nothing else. */
class ModLoadCompletionTest {

    @TempDir
    Path tmp;

    private Completions completions;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("modules"));
        Files.writeString(tmp.resolve("modules").resolve("greet.JAR"), "x");
        Files.writeString(tmp.resolve("modules").resolve("notes.txt"), "x");
        Files.writeString(tmp.resolve("samples.jar"), "x");
        Files.writeString(tmp.resolve("samples.txt"), "x");
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of()));
        completions = new Completions(interpreter);
    }

    private List<String> offered(String line) {
        var result = completions.complete(line, line.length());
        return result.candidates().stream().map(Completions.Candidate::value)
                .filter(v -> v.startsWith(result.word())).toList();
    }

    @Test
    void foldersAndJarsAreOffered() {
        assertThat(offered("mod-load ")).containsExactlyInAnyOrder("modules" + File.separator, "samples.jar");
        assertThat(offered("mod-load s")).containsExactly("samples.jar");
    }

    @Test
    void otherFilesAreNotOffered() {
        assertThat(offered("mod-load samples.t")).isEmpty();
    }

    @Test
    void aFolderNamedLikeAJarIsOfferedAsAFolderAndNotAsAFile() throws Exception {
        Files.createDirectories(tmp.resolve("dir.jar"));
        assertThat(offered("mod-load d")).containsExactly("dir.jar" + File.separator);
    }

    @Test
    void aForcedNativeCommandKeepsEveryFile() {
        assertThat(offered("^mod-load s")).containsExactlyInAnyOrder("samples.jar", "samples.txt");
    }

    @Test
    void aQuotedPathIsCompleted() {
        assertThat(offered("mod-load \"mod")).containsExactly("modules" + File.separator);
        assertThat(offered("mod-load \"modules/g")).containsExactly("modules/greet.JAR");
    }

    @Test
    void aPathStartingWithTheHomeFolderIsCompleted() {
        assertThat(offered("mod-load ~/")).containsExactlyInAnyOrder("~/modules" + File.separator, "~/samples.jar");
    }

    @Test
    void thePathIsCompletedInsideAFolder() {
        assertThat(offered("mod-load modules/")).containsExactly("modules/greet.JAR");
    }
}
