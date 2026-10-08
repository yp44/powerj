package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Completions;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;

/** Tab completion (FR-21 to FR-25, FR-24b) with the real cmdlets. */
class CompletionsTest {

    @TempDir
    Path tmp;

    private Interpreter interpreter;
    private Completions completions;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("docs"));
        Files.createDirectories(tmp.resolve("Program Files"));
        Files.writeString(tmp.resolve("notes.txt"), "x");
        Files.writeString(tmp.resolve("docs/spec.md"), "x");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path tool = Files.writeString(bin.resolve("lsblk-like"), "#!/bin/sh\n");
        tool.toFile().setExecutable(true);
        Files.writeString(bin.resolve("lsblk-like.exe"), "");
        Map<String, String> env = new HashMap<>();
        env.put("PATH", bin.toString());
        env.put("PATHEXT", ".EXE");
        var session = new Session(tmp, tmp, env);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.discover());
        completions = new Completions(interpreter);
    }

    private Completions.Result complete(String line) {
        return completions.complete(line, line.length());
    }

    /** Proposed values that extend the current word (the final filtering is done by JLine). */
    private List<String> values(String line) {
        var result = complete(line);
        return result.candidates().stream().map(Completions.Candidate::value)
                .filter(v -> v.startsWith(result.word())).toList();
    }

    private List<String> displays(String line) {
        var result = complete(line);
        return result.candidates().stream().filter(c -> c.value().startsWith(result.word()))
                .map(Completions.Candidate::display).toList();
    }

    @Test
    void commandsWithTheirNature() {
        assertThat(displays("l")).contains("ls [pj]", "lsblk-like [natif]");
        assertThat(displays("wh")).contains("where [pj]", "which [interne]");
        assertThat(values("^l")).containsExactly("lsblk-like");
        assertThat(values("ls | wh")).contains("where");
        assertThat(values("$x = l")).contains("ls");
    }

    @Test
    void optionsExcludeThoseAlreadyTyped() {
        assertThat(values("ls --")).contains("--recurse", "--all", "--filter", "--on-error", "--help");
        assertThat(values("ls -r --")).doesNotContain("--recurse").contains("--all");
        assertThat(values("ls --recurse --")).doesNotContain("--recurse");
        assertThat(displays("ls --r")).contains("--recurse, -r");
    }

    @Test
    void paths() {
        assertThat(values("ls d")).containsExactly("docs" + java.io.File.separator);
        assertThat(values("ls docs/")).containsExactly("docs/spec.md");
        assertThat(values("cat no")).containsExactly("notes.txt");
        assertThat(values("ls Prog")).containsExactly("Program Files" + java.io.File.separator);
        var quoted = complete("ls \"Prog");
        assertThat(quoted.start()).isEqualTo(3);
        assertThat(quoted.word()).isEqualTo("Prog");
        assertThat(values("cd do")).containsExactly("docs" + java.io.File.separator);
    }

    @Test
    void propertiesOfTheUpstreamCmdlet() {
        assertThat(values("ls | where { $_.")).contains("name", "size", "modified", "path", "dir", "ext");
        assertThat(values("ls | where { f -> f.na")).contains("name");
        assertThat(values("ls | where { f -> f.name.sta")).contains("startsWith(");
        assertThat(values("ls | collect | map { l -> l.str")).contains("stream()");
        assertThat(values("ls | collect | map { l -> l.si")).contains("size()");
        assertThat(values("ls -r | where { $_.dir } | map { $_.ex")).contains("ext");
        assertThat(values("env | where { $_.")).contains("name", "value");
    }

    @Test
    void variablesAndTheirMembers() throws Exception {
        interpreter.execute("$f = ls");
        interpreter.execute("$l = List.of(1, 2)");
        assertThat(values("$")).contains("$f", "$l", "$exit", "$last");
        assertThat(values("$f[0].")).contains("name", "size", "path");
        assertThat(values("$l.")).contains("size()", "get(", "stream()");
        assertThat(values("$l.stream().fil")).contains("filter(");
        assertThat(values("$f*.na")).contains("name");
        assertThat(displays("$l.si")).contains("size() : int");
    }

    @Test
    void javaPackagesClassesAndStaticMembers() {
        assertThat(values("java.util.Li")).contains("List", "LinkedList");
        assertThat(values("java.ut")).contains("util");
        assertThat(values("List.")).contains("of(", "copyOf(");
        assertThat(displays("List.of")).contains("of(Object...) : List");
        assertThat(values("Math.P")).contains("PI");
        assertThat(values("new java.io.F")).contains("File", "FileReader");
        assertThat(values("Ma")).contains("Math");
        assertThat(values("ls | where { List.of(\"a\").str")).contains("stream()");
        assertThat(values("ls | map String::len")).contains("length");
        assertThat(values("import java.sec")).contains("java.security.");
        assertThat(values("import java.security.Mess")).contains("java.security.MessageDigest");
    }

    @Test
    void nothingBreaksOnOddInput() {
        for (String line : List.of("", "\"", "{", "ls | where { $_.size > ", "((", "$", "ls --filter=", "]]", "a.b.c.")) {
            assertThat(completions.complete(line, line.length())).isNotNull();
        }
    }
}
