package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.powerj.api.ScriptBlock;
import io.powerj.api.Span;

class GrepTest {

    @TempDir
    Path tmp;

    private TestContext<Object> context;

    @BeforeEach
    void tree() throws Exception {
        Files.createDirectories(tmp.resolve("src/deep"));
        Files.writeString(tmp.resolve("a.txt"), "first line\nTODO: write\nlast TODO todo\n");
        Files.writeString(tmp.resolve("src/B.java"), "class B {\n  // TODO later\n}\n");
        Files.writeString(tmp.resolve("src/deep/C.java"), "class C { } // todo\n");
        Files.write(tmp.resolve("image.bin"), new byte[] {'T', 'O', 'D', 'O', 0, 1, 2});
        Path hidden = Files.writeString(tmp.resolve(".secret"), "TODO hidden\n");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Files.setAttribute(hidden, "dos:hidden", true);
        }
        context = new TestContext<>(tmp);
    }

    private static Grep.Params params(String pattern, String... paths) {
        return new Grep.Params(pattern, List.of(paths), false, null, null, false, false, false, null, null, false, false);
    }

    private static Grep.Params recursive(String pattern, Integer parallel) {
        return new Grep.Params(pattern, List.of(), true, parallel, null, false, false, false, null, null, false, false);
    }

    private List<GrepMatch> run(Grep.Params params) throws Exception {
        var grep = new Grep();
        grep.begin(params, context);
        grep.end(params, context);
        context.closeStates();
        return context.emitted.stream().map(GrepMatch.class::cast).toList();
    }

    private List<String> found(Grep.Params params) throws Exception {
        return run(params).stream().map(m -> tmp.relativize(m.path()).toString().replace('\\', '/') + ":" + m.line())
                .toList();
    }

    @Test
    void searchesAFileWithAbsolutePathLineNumberAndText() throws Exception {
        List<GrepMatch> matches = run(params("TODO", "a.txt"));
        assertThat(matches).hasSize(2);
        GrepMatch first = matches.getFirst();
        assertThat(first.path()).isEqualTo(tmp.resolve("a.txt").toAbsolutePath());
        assertThat(first.path().isAbsolute()).isTrue();
        assertThat(first.line()).isEqualTo(2);
        assertThat(first.text()).isEqualTo("TODO: write");
        assertThat(first.matches()).containsExactly(new Span(0, 4));
        assertThat(matches.get(1).matches()).containsExactly(new Span(5, 9));
        assertThat(context.errors).isEmpty();
    }

    @Test
    void withoutRecurseOnlyTheFilesOfTheDirectory() throws Exception {
        assertThat(found(params("TODO"))).containsExactly("a.txt:2", "a.txt:3");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void recurseWalksSubdirectoriesSequentiallyOrInParallel(int parallel) throws Exception {
        assertThat(found(recursive("TODO", parallel))).containsExactlyInAnyOrder("a.txt:2", "a.txt:3", "src/B.java:2");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void linesOfAFileStayGroupedAndInOrderInParallel() throws Exception {
        for (int i = 0; i < 30; i++) {
            Files.writeString(tmp.resolve("src/f" + i + ".txt"), "x\n".repeat(50));
        }
        List<GrepMatch> matches = run(recursive("x", 8));
        assertThat(matches).hasSize(30 * 50);
        for (int i = 0; i < matches.size(); i += 50) {
            Path file = matches.get(i).path();
            for (int line = 0; line < 50; line++) {
                assertThat(matches.get(i + line).path()).isEqualTo(file);
                assertThat(matches.get(i + line).line()).isEqualTo(line + 1);
            }
        }
    }

    @Test
    void ignoreCaseAndInvert() throws Exception {
        var ignoreCase = new Grep.Params("todo", List.of("a.txt"), false, null, null, true, false, false, null, null, false, false);
        assertThat(found(ignoreCase)).containsExactly("a.txt:2", "a.txt:3");
        assertThat(run(ignoreCase).getLast().matches()).containsExactly(new Span(5, 9), new Span(10, 14));
        context.emitted.clear();
        var invert = new Grep.Params("TODO", List.of("a.txt"), false, null, null, false, true, false, null, null, false, false);
        List<GrepMatch> matches = run(invert);
        assertThat(matches).singleElement().satisfies(m -> {
            assertThat(m.line()).isEqualTo(1);
            assertThat(m.matches()).isEmpty();
        });
    }

    @Test
    void maxDepthImpliesRecursion() throws Exception {
        var depth2 = new Grep.Params("(?i)todo", List.of(), false, null, 2, false, false, false, null, null, false, false);
        assertThat(found(depth2)).containsExactlyInAnyOrder("a.txt:2", "a.txt:3", "src/B.java:2");
    }

    @Test
    void groupsAndNamedGroupsOfTheFirstMatch() throws Exception {
        List<GrepMatch> matches = run(params("class (?<name>\\w+)(x)?", "src/B.java"));
        GrepMatch match = matches.getFirst();
        assertThat(match.groups()).containsExactly("class B", "B", null);
        assertThat(match.named()).isEqualTo(Map.of("name", "B"));
    }

    @Test
    void binaryAndHiddenFilesAreSkippedUnlessAll() throws Exception {
        assertThat(found(params("TODO"))).doesNotContain("image.bin:1", ".secret:1");
        context.emitted.clear();
        var all = new Grep.Params("TODO", List.of(), false, null, null, false, false, true, null, null, false, false);
        assertThat(found(all)).contains(".secret:1").doesNotContain("image.bin:1");
    }

    @Test
    void binaryOptionDisablesTheBinaryCheck() throws Exception {
        var binary = new Grep.Params("TODO", List.of("image.bin"), false, null, null, false, false, false,
                "ISO-8859-1", null, true, false);
        assertThat(found(binary)).containsExactly("image.bin:1");
        assertThat(context.errors).isEmpty();
    }

    private static Grep.Params singleMatch(boolean invert, Integer parallel) {
        return new Grep.Params("(?i)todo", List.of(), true, parallel, null, false, invert, false, null, null, false,
                true);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void singleMatchGivesTheFirstMatchingLineOfEachFile(int parallel) throws Exception {
        assertThat(found(singleMatch(false, parallel)))
                .containsExactlyInAnyOrder("a.txt:2", "src/B.java:2", "src/deep/C.java:1");
        assertThat(run(singleMatch(false, parallel))).allSatisfy(m -> assertThat(m.matches()).isNotEmpty());
        assertThat(context.errors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void singleMatchWithInvertGivesTheFilesThatNeverMatch(int parallel) throws Exception {
        Files.writeString(tmp.resolve("src/clean.txt"), "nothing here\n");
        List<GrepMatch> matches = run(singleMatch(true, parallel));
        assertThat(matches).singleElement().satisfies(m -> {
            assertThat(m.path()).isEqualTo(tmp.resolve("src/clean.txt").toAbsolutePath());
            assertThat(m.line()).isZero();
            assertThat(m.text()).isEmpty();
            assertThat(m.matches()).isEmpty();
        });
        assertThat(context.errors).isEmpty();
    }

    @Test
    void singleMatchStopsReadingTheFileAtItsFirstMatch() throws Exception {
        // invalid UTF-8 far after the matching first line (beyond the read-ahead buffers): only a full read meets it
        byte[] head = ("TODO first\n" + "filler line\n".repeat(10_000)).getBytes(StandardCharsets.UTF_8);
        byte[] content = java.util.Arrays.copyOf(head, head.length + 3);
        content[head.length] = (byte) 0xC3;
        content[head.length + 1] = (byte) 0x28;
        content[head.length + 2] = '\n';
        Files.createDirectories(tmp.resolve("only"));
        Files.write(tmp.resolve("only/stop.txt"), content);
        var single = new Grep.Params("TODO", List.of("only"), false, null, null, false, false, false, null, null,
                false, true);
        assertThat(found(single)).containsExactly("only/stop.txt:1");
        assertThat(context.errors).isEmpty();
        context.emitted.clear();
        var singleInvert = new Grep.Params("TODO", List.of("only"), false, null, null, false, true, false, null,
                null, false, true);
        assertThat(run(singleInvert)).isEmpty();
        assertThat(context.errors).isEmpty();
        var full = new Grep.Params("TODO", List.of("only"), false, null, null, false, false, false, null, null,
                false, false);
        run(full);
        assertThat(context.errors).singleElement().asString().contains("UTF-8");
    }

    @Test
    void wildcards() throws Exception {
        assertThat(found(params("(?i)todo", "src/*.java"))).containsExactly("src/B.java:2");
        context.emitted.clear();
        assertThat(found(params("x", "*.none"))).isEmpty();
        assertThat(context.errors).containsExactly("no file matches *.none");
    }

    @Test
    void wildcardsKeepCaseUnlessTheVariableSaysOtherwise() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the matcher of Windows ignores case");
        assertThat(found(params("(?i)todo", "src/*.JAVA"))).isEmpty();
        assertThat(context.errors).containsExactly("no file matches src/*.JAVA");
        context.errors.clear();

        context.setEnvironment("POWERJ_GLOB_IGNORE_CASE", "true");

        assertThat(found(params("(?i)todo", "src/*.JAVA"))).containsExactly("src/B.java:2");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void missingFileIsANonBlockingError() throws Exception {
        assertThat(found(params("TODO", "absent.txt", "a.txt"))).containsExactly("a.txt:2", "a.txt:3");
        assertThat(context.errors).singleElement().asString().startsWith("not found: ");
    }

    @Test
    void utf8WithoutFallbackAndEncodingOption() throws Exception {
        Files.write(tmp.resolve("latin.txt"), "café TODO\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(found(params("TODO", "latin.txt"))).isEmpty();
        assertThat(context.errors).singleElement().asString().contains("UTF-8").contains("--encoding");
        context.errors.clear();
        var latin = new Grep.Params("café", List.of("latin.txt"), false, null, null, false, false, false,
                "ISO-8859-1", null, false, false);
        assertThat(run(latin)).singleElement().extracting(GrepMatch::text).isEqualTo("café TODO");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> run(params("(unclosed"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid regular expression (unclosed");
        assertThatThrownBy(() -> run(new Grep.Params("x", List.of(), false, null, null, false, false, false,
                "nope-charset", null, false, false))).hasMessage("unknown charset: nope-charset");
        assertThatThrownBy(() -> run(recursive("x", 0))).hasMessageContaining("--parallel");
    }

    @Test
    void filtersStringsOfThePipeline() throws Exception {
        context.hasInput = true;
        var grep = new Grep();
        var params = new Grep.Params("fix", List.of(), false, null, null, true, false, false, null, null, false, false);
        grep.begin(params, context);
        for (String line : List.of("a1 Fix crash", "b2 add feature", "c3 fix typo")) {
            grep.process(params, line, context);
        }
        grep.end(params, context);
        context.closeStates();
        assertThat(context.emitted).containsExactly("a1 Fix crash", "c3 fix typo");
    }

    @Test
    void filesOfThePipelineAreSearched() throws Exception {
        context.hasInput = true;
        var grep = new Grep();
        var params = params("TODO");
        grep.begin(params, context);
        assertThat(context.emitted).isEmpty(); // input expected: no search in the current directory
        grep.process(params, new FileEntry("B.java", 0, Instant.EPOCH, tmp.resolve("src/B.java"), false, "java"), context);
        grep.process(params, tmp.resolve("src"), context); // directory without -r: skipped
        grep.end(params, context);
        context.closeStates();
        assertThat(context.emitted).singleElement().isInstanceOf(GrepMatch.class);
    }

    @Test
    void propertyOfThePipelineObjects() throws Exception {
        context.hasInput = true;
        context.compiler = source -> {
            assertThat(source).isEqualTo("$_.name");
            return new ScriptBlock() {
                @Override
                public Object invoke(Object current) {
                    return ((FileEntry) current).name();
                }

                @Override
                public String source() {
                    return source;
                }
            };
        };
        var grep = new Grep();
        var params = new Grep.Params("^a", List.of(), false, null, null, false, false, false, null, "name", false, false);
        grep.begin(params, context);
        var a = new FileEntry("a.txt", 0, Instant.EPOCH, tmp.resolve("a.txt"), false, "txt");
        var b = new FileEntry("b.txt", 0, Instant.EPOCH, tmp.resolve("b.txt"), false, "txt");
        grep.process(params, a, context);
        grep.process(params, b, context);
        grep.end(params, context);
        context.closeStates();
        assertThat(context.emitted).containsExactly(a);
    }

    private static Grep.Params full(String pattern, Integer maxDepth, boolean invert, String encoding, String property) {
        return new Grep.Params(pattern, List.of(), false, null, maxDepth, false, invert, false, encoding, property,
                false, false);
    }

    @Test
    void maxDepthAndPropertyMustBeValid() {
        assertThatThrownBy(() -> run(full("x", 0, false, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--max-depth expects a depth of at least 1, got 0");
        assertThatThrownBy(() -> run(full("x", null, false, null, "1x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid property name: 1x");
    }

    @Test
    void propertyErrorsSkipTheObjectAndNullIsEmptyText() throws Exception {
        context.hasInput = true;
        context.compiler = source -> new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                if (current.equals("bad")) {
                    throw new IllegalStateException("no property name");
                }
                return current.equals("none") ? null : current;
            }

            @Override
            public String source() {
                return source;
            }
        };
        var grep = new Grep();
        var params = full("^$", null, false, null, "name");
        grep.process(params, "bad", context); // without begin: the search is created on first use
        grep.process(params, "none", context);
        grep.process(params, "text", context);
        grep.end(params, context);
        context.closeStates();
        assertThat(context.emitted).containsExactly("none");
        assertThat(context.errors).containsExactly("no property name (object skipped: bad)");
    }

    @Test
    void aFolderFromThePipelineIsSearchedOnlyWithADepth() throws Exception {
        context.hasInput = true;
        var grep = new Grep();
        var flat = full("todo", null, false, null, null);
        grep.begin(flat, context);
        grep.process(flat, tmp.resolve("src"), context);
        grep.end(flat, context);
        context.closeStates();
        assertThat(context.emitted).isEmpty();

        var deep = full("todo", 2, false, null, null);
        grep.begin(deep, context);
        grep.process(deep, tmp.resolve("src"), context);
        grep.end(deep, context);
        context.closeStates();
        assertThat(context.emitted).extracting(m -> ((GrepMatch) m).path().getFileName().toString())
                .containsExactly("C.java");
    }

    @Test
    void utf16FilesAreNotTakenForBinaryOnes() throws Exception {
        Files.writeString(tmp.resolve("wide.txt"), "find me\n", java.nio.charset.StandardCharsets.UTF_16LE);
        var params = new Grep.Params("find", List.of("wide.txt"), false, null, null, false, false, false, "UTF-16LE",
                null, false, false);
        assertThat(found(params)).containsExactly("wide.txt:1");
    }
}
