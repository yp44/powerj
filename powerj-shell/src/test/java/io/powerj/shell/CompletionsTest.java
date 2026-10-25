package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertThat(displays("l")).contains("ls [pj]", "lsblk-like [native]");
        assertThat(displays("wh")).contains("where [pj]", "which [internal]");
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

    @Test
    void previewModeOffersOnlyWhatCanRunInAPreview() {
        completions.previewMode(true);
        assertThat(values("l")).contains("ls").doesNotContain("lsblk-like");
        assertThat(values("c")).contains("cat", "collect").doesNotContain("cd");
        assertThat(values("java.nio.file.Files.")).contains("exists(", "readString(").doesNotContain("delete(", "writeString(");
        assertThat(values("\"abc\".")).contains("toUpperCase()");
        completions.previewMode(false);
        assertThat(values("l")).contains("ls", "lsblk-like");
        assertThat(values("java.nio.file.Files.")).contains("delete(");
    }

    @Test
    void selectedColumnsAreProposedDownstream() {
        assertThat(values("ls | select name size | where { $_.")).contains("name", "size").doesNotContain("dir");
        assertThat(values("ls | select file=name kb={ $_.size / 1kb } | sort { r -> r.")).contains("file", "kb");
        assertThat(values("ls | select path.fileName { $_.name } | where { $_.")).contains("fileName", "column1");
        // The columns go through the stages declared passThrough (sort, first…).
        assertThat(values("ls | select name kb={ $_.size / 1kb } | sort kb -d | first 3 | where { $_."))
                .contains("name", "kb").doesNotContain("dir");
        // measure declares its columns whatever its arguments.
        assertThat(values("ls | measure size | where { $_.")).contains("count", "sum", "average", "min", "max");
    }

    @Test
    void typeGoesThroughTheStagesThatLetObjectsThrough() {
        assertThat(values("ls | sort name | map { $_.")).contains("name", "size", "dir");
        assertThat(values("ls | first 3 | map { f -> f.na")).contains("name");
        assertThat(values("ls | last 2 | where { $_.")).contains("size");
        assertThat(values("ls | range --from 2 | map { $_.")).contains("ext");
    }

    @Test
    void groupPropertiesAreProposedDownstream() {
        assertThat(values("ls | group ext | where { $_.")).contains("key", "count", "items");
    }

    @Test
    void everyStatementSeparatorStartsANewCommand() {
        assertThat(values("ls || wh")).contains("where");
        assertThat(values("ls ; wh")).contains("where");
        assertThat(values("ls && wh")).contains("where");
        assertThat(values("ls & wh")).contains("where");
        // An & inside a word is not a background: still an argument of ls.
        assertThat(values("ls a&b")).isEmpty();
    }

    @Test
    void stringsWithEscapedCharacters() {
        assertThat(values("cat \"a\\\"b\" no")).containsExactly("notes.txt");
        assertThat(values("ls \"Prog\\ra")).containsExactly("Program Files" + java.io.File.separator);
    }

    @Test
    void pathsFromHomeHiddenFilesAndMissingFolders() throws Exception {
        Files.writeString(tmp.resolve(".hidden"), "x");
        assertThat(values("ls ~/do")).containsExactly("~/docs/");
        assertThat(values("ls ")).contains("notes.txt").doesNotContain(".hidden");
        assertThat(values("ls \".h")).containsExactly(".hidden");
        assertThat(values("ls nope/x")).isEmpty();
        assertThat(values("^ls do")).containsExactly("docs" + java.io.File.separator);
    }

    @Test
    void manyFilesAreCappedAt500() throws Exception {
        Path many = Files.createDirectories(tmp.resolve("many"));
        for (int i = 0; i < 510; i++) {
            Files.writeString(many.resolve("f" + i), "");
        }
        assertThat(complete("ls many/").candidates()).hasSize(500);
    }

    @Test
    void currentObjectAndClassesInBlocks() throws Exception {
        assertThat(values("ls | where { $")).contains("$_");
        assertThat(values("ls | map { new StringB")).contains("StringBuilder");
        assertThat(values("ls | map { Str")).contains("String", "StringBuilder");
        assertThat(values("ls | map { tr")).contains("true");
        interpreter.execute("import java.security.MessageDigest");
        assertThat(values("ls | map { new MessageD")).contains("MessageDigest");
    }

    @Test
    void importStartsWithTheRootPackages() {
        assertThat(values("import ja")).contains("java.");
    }

    @Test
    void textComesFromNativeCommands() {
        assertThat(values("^ls | where { $_.")).contains("length()", "isBlank()");
        assertThat(values("lsblk-like | where { $_.")).contains("length()");
        assertThat(values("nope | where { $_.")).isEmpty();
        assertThat(values("ls | map { $_ } | where { $_.")).isEmpty();
        assertThat(values("ls | select | where { $_.")).doesNotContain("name");
    }

    @Test
    void typesOfExpressions() throws Exception {
        interpreter.execute("$f = ls");
        interpreter.execute("$l = List.of(1, 2)");
        interpreter.execute("$s = \"abc\"");
        assertThat(values("(ls).")).contains("size()", "stream()");
        assertThat(values("(ls)[0].")).contains("name", "size");
        assertThat(values("(ls)*.na")).contains("name");
        assertThat(values("(^ls)[0].")).contains("length()");
        assertThat(values("(ls | map { $_ }).")).isEmpty();
        assertThat(values("(ls).stream().")).contains("filter(");
        assertThat(values("(ls)[0].path.")).contains("fileName", "getName(");
        assertThat(values("(ls)[0].path.fileName.")).contains("fileName", "getName(");
        assertThat(values("$f[0].path.")).contains("fileName", "getName(");
        assertThat(values("$f*.name.")).contains("size()");
        assertThat(values("$l[-1].")).contains("intValue()");
        assertThat(values("$l[5].")).contains("intValue()");
        assertThat(values("$s*.len")).contains("length()");
        assertThat(values("$l.clear().")).isEmpty();
        assertThat(values("(new StringBuilder()).")).contains("append(", "reverse()");
        assertThat(values("true.")).contains("booleanValue()");
        assertThat(values("null.")).isEmpty();
        assertThat(values("[1, 2].")).contains("size()");
        assertThat(values("now.")).contains("epochSecond");
        assertThat(values("\"a,b\".split(\",\")[0].")).contains("length()");
        assertThat(values("\"abc\".substring().")).contains("length()");
        assertThat(values("String.valueOf(1).")).contains("length()");
        assertThat(values("System.out.")).contains("println(");
        assertThat(values("Map.")).contains("Entry", "of(");
        assertThat(values("Map.Entry.")).contains("comparingByKey()");
        assertThat(values("ls | where { a).")).isEmpty();
    }

    @Test
    void methodReferences() throws Exception {
        interpreter.execute("$l = List.of(1, 2)");
        assertThat(values("ls | map $l::")).contains("size", "get");
        assertThat(values("ls | map List::")).contains("of").doesNotContain("new");
        assertThat(values("ls | map StringBuilder::")).contains("new", "append");
    }

    @Test
    void optionValuesAndCommandsWithoutArguments() {
        assertThat(complete("ls -f ").candidates()).isEmpty();
        assertThat(complete("ls --filter ").candidates()).isEmpty();
        assertThat(values("ls --filter=*.md --")).doesNotContain("--filter").contains("--all");
        assertThat(values("ls --on-error stop --")).doesNotContain("--on-error");
        assertThat(complete("pwd ").candidates()).isEmpty();
        assertThat(complete("where ").candidates()).isEmpty();
    }
}
