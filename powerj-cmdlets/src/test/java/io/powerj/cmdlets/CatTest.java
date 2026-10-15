package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.ScriptBlock;

class CatTest {

    @TempDir
    Path tmp;

    private TestContext<Object> context;

    @BeforeEach
    void files() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "first\nsecond\nthird\n");
        Files.writeString(tmp.resolve("b.txt"), "un\r\ndeux");
        Files.writeString(tmp.resolve("empty.txt"), "");
        Files.createDirectories(tmp.resolve("dir"));
        Files.writeString(tmp.resolve("notes.md"), "# notes\n");
        Files.write(tmp.resolve("latin.txt"), "café\n".getBytes(StandardCharsets.ISO_8859_1));
        context = new TestContext<>(tmp);
    }

    private static Cat.Params params(Cat.Mode mode, String... paths) {
        return new Cat.Params(List.of(paths), mode, null, null);
    }

    private List<Object> run(Cat.Params params, Object... inputs) {
        var cat = new Cat();
        context.hasInput = inputs.length > 0;
        cat.begin(params, context);
        for (Object input : inputs) {
            cat.process(params, input, context);
        }
        cat.end(params, context);
        return context.emitted;
    }

    private List<CatLine> numbered(Cat.Params params, Object... inputs) {
        return run(params, inputs).stream().map(CatLine.class::cast).toList();
    }

    @Test
    void linesByDefaultAStringPerLineWithoutEndOfLine() {
        assertThat(run(params(null, "a.txt"))).containsExactly("first", "second", "third");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void severalFilesOneAfterTheOther() {
        assertThat(run(params(Cat.Mode.LINES, "a.txt", "b.txt"))).containsExactly("first", "second", "third", "un", "deux");
    }

    @Test
    void emptyFileGivesNoLine() {
        assertThat(run(params(Cat.Mode.LINES, "empty.txt"))).isEmpty();
        assertThat(context.errors).isEmpty();
    }

    @Test
    void numberedLinesGoOnFromOneFileToTheNext() {
        List<CatLine> lines = numbered(params(Cat.Mode.NUMBERED, "a.txt", "empty.txt", "b.txt"));
        assertThat(lines).extracting(CatLine::line).containsExactly(1, 2, 3, 4, 5);
        assertThat(lines).extracting(CatLine::text).containsExactly("first", "second", "third", "un", "deux");
        assertThat(lines).extracting(CatLine::length).containsExactly(5, 6, 5, 2, 4);
        assertThat(lines.get(0).path()).isEqualTo(tmp.resolve("a.txt").toAbsolutePath());
        assertThat(lines.get(3).path()).isEqualTo(tmp.resolve("b.txt").toAbsolutePath());
        assertThat(lines.get(0).path().isAbsolute()).isTrue();
    }

    @Test
    void textIsOneStringWithTheFilesConcatenatedAsIs() {
        assertThat(run(params(Cat.Mode.TEXT, "a.txt", "b.txt"))).containsExactly("first\nsecond\nthird\nun\r\ndeux");
    }

    @Test
    void contentIsOneRecordWithTheNumberOfCharacters() {
        List<Object> out = run(params(Cat.Mode.CONTENT, "b.txt", "a.txt"));
        String expected = "un\r\ndeuxfirst\nsecond\nthird\n";
        assertThat(out).containsExactly(new CatContent(expected.length(), expected));
    }

    @Test
    void contentOfAnEmptyFileIsEmpty() {
        assertThat(run(params(Cat.Mode.CONTENT, "empty.txt"))).containsExactly(new CatContent(0, ""));
    }

    @Test
    void wildcardsInNameOrder() {
        assertThat(run(params(Cat.Mode.LINES, "?.txt"))).containsExactly("first", "second", "third", "un", "deux");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void wildcardWithoutMatchIsNonBlocking() {
        assertThat(run(params(Cat.Mode.LINES, "*.xml", "b.txt"))).containsExactly("un", "deux");
        assertThat(context.errors).containsExactly("no file matches *.xml");
    }

    @Test
    void missingFileAndDirectoryAreNonBlocking() {
        assertThat(run(params(Cat.Mode.LINES, "missing.txt", "dir", "b.txt"))).containsExactly("un", "deux");
        assertThat(context.errors).containsExactly(
                "not found: " + tmp.resolve("missing.txt").toAbsolutePath(),
                "is a directory: " + tmp.resolve("dir").toAbsolutePath());
    }

    @Test
    void undecodableFileSuggestsEncodingAndAddsNothingToTheContent() {
        assertThat(run(params(Cat.Mode.TEXT, "latin.txt", "b.txt"))).containsExactly("un\r\ndeux");
        assertThat(context.errors).containsExactly("cannot read " + tmp.resolve("latin.txt").toAbsolutePath()
                + ": not readable as UTF-8 text (choose the charset with --encoding)");
    }

    @Test
    void encodingReadsOtherCharsets() {
        assertThat(run(new Cat.Params(List.of("latin.txt"), null, "ISO-8859-1", null))).containsExactly("café");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void unknownEncodingIsBlocking() {
        assertThatThrownBy(() -> run(new Cat.Params(List.of("a.txt"), null, "nope", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown charset: nope");
    }

    @Test
    void withoutFileNorPipelineInputIsBlocking() {
        assertThatThrownBy(() -> run(params(Cat.Mode.LINES))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file expected: cat <file>... or ... | cat");
    }

    @Test
    void pipelineStringsPathsFilesAndFileEntriesAreReadAsArguments() {
        FileEntry entry = new FileEntry("b.txt", 8, Instant.EPOCH, tmp.resolve("b.txt"), false, "txt");
        List<CatLine> lines = numbered(params(Cat.Mode.NUMBERED), "a.txt", Path.of("empty.txt"),
                tmp.resolve("notes.md").toFile(), entry);
        assertThat(lines).extracting(CatLine::line).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(lines).extracting(CatLine::text).containsExactly("first", "second", "third", "# notes", "un", "deux");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void numberingGoesOnFromTheArgumentsToThePipelineObjects() {
        List<CatLine> lines = numbered(params(Cat.Mode.NUMBERED, "b.txt"), "a.txt");
        assertThat(lines).extracting(CatLine::line).containsExactly(1, 2, 3, 4, 5);
        assertThat(lines).extracting(CatLine::text).containsExactly("un", "deux", "first", "second", "third");
    }

    @Test
    void pipelineContentIsEmittedOnceAtTheEnd() {
        assertThat(run(params(Cat.Mode.TEXT), "b.txt", "a.txt")).containsExactly("un\r\ndeuxfirst\nsecond\nthird\n");
    }

    @Test
    void directoriesListedByLsAreSkipped() {
        FileEntry dir = new FileEntry("dir", 0, Instant.EPOCH, tmp.resolve("dir"), true, "");
        assertThat(run(params(Cat.Mode.LINES), dir, "b.txt")).containsExactly("un", "deux");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void otherObjectsWithoutPropertyAreSkippedWithAnError() {
        assertThat(run(params(Cat.Mode.LINES), 42, "b.txt")).containsExactly("un", "deux");
        assertThat(context.errors).containsExactly(
                "not a file: 42 (name the property that gives the file with --property)");
    }

    private void property(String expected, Function<Object, Object> getter) {
        context.compiler = source -> {
            assertThat(source).isEqualTo("$_." + expected);
            return new ScriptBlock() {
                @Override
                public Object invoke(Object current) {
                    return getter.apply(current);
                }

                @Override
                public String source() {
                    return source;
                }
            };
        };
    }

    @Test
    void propertyGivesTheFileOfOtherObjects() {
        property("path", o -> ((GrepMatch) o).path());
        var match = new GrepMatch(tmp.resolve("b.txt"), 1, "un", List.of(), List.of(), Map.of());
        List<Object> out = run(new Cat.Params(List.of(), Cat.Mode.LINES, null, "path"), match);
        assertThat(out).containsExactly("un", "deux");
    }

    @Test
    void propertyStringIsAPathRelativeToTheCurrentDirectory() {
        property("value", o -> ((EnvVar) o).value());
        List<Object> out = run(new Cat.Params(List.of(), Cat.Mode.LINES, null, "value"), new EnvVar("F", "a.txt"));
        assertThat(out).containsExactly("first", "second", "third");
    }

    @Test
    void propertyThatIsNotAFileSkipsTheObject() {
        property("line", o -> ((GrepMatch) o).line());
        var match = new GrepMatch(tmp.resolve("b.txt"), 1, "un", List.of(), List.of(), Map.of());
        assertThat(run(new Cat.Params(List.of(), Cat.Mode.LINES, null, "line"), match)).isEmpty();
        assertThat(context.errors).hasSize(1).first().asString()
                .startsWith("property line is not a file (String, Path or File expected): ");
    }

    @Test
    void invalidPropertyNameIsBlocking() {
        assertThatThrownBy(() -> run(new Cat.Params(List.of(), Cat.Mode.LINES, null, "a b"), "a.txt"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid property name: a b");
    }
}
