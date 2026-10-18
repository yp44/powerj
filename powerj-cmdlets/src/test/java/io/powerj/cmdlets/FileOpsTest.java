package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;

/** {@code mkdir}, {@code touch}, {@code cp}, {@code mv} and {@code rm} (FR-72), always inside a temporary folder. */
class FileOpsTest {

    @TempDir
    Path tmp;

    private Path work;
    private Path config;
    private TestContext<Object> context;

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(tmp.resolve("work"));
        config = Files.createDirectories(tmp.resolve("config"));
        Files.write(config.resolve(ProtectedPaths.FILE_NAME), List.of("# test list", tmp.resolve("work/keep").toString()));
        context = newContext();
    }

    private TestContext<Object> newContext() {
        var c = new TestContext<Object>(work);
        c.configDirectory = config;
        return c;
    }

    private static List<String> args(String... args) {
        return new ArrayList<>(Arrays.asList(args));
    }

    private <P extends Record> List<Object> run(Cmdlet<P, Object, Object> cmdlet, P params, Object... inputs)
            throws Exception {
        context.hasInput = inputs.length > 0;
        cmdlet.begin(params, context);
        for (Object input : inputs) {
            cmdlet.process(params, input, context);
        }
        cmdlet.end(params, context);
        return context.emitted;
    }

    private Path file(String name, String content) throws IOException {
        Path path = work.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    private List<Object> mkdir(boolean parents, boolean dryRun, String... paths) throws Exception {
        return run(new MakeDirectory(), new MakeDirectory.Params(args(paths), parents, dryRun, null));
    }

    private List<Object> cp(boolean recurse, boolean force, boolean verbose, boolean dryRun, List<String> paths,
                            Object... inputs) throws Exception {
        return run(new Copy(), new Copy.Params(paths, recurse, force, verbose, dryRun, null), inputs);
    }

    private List<Object> mv(boolean force, boolean allowProtected, String... paths) throws Exception {
        return run(new Move(), new Move.Params(args(paths), force, true, false, allowProtected, null));
    }

    private List<Object> rm(boolean recurse, boolean force, boolean verbose, boolean dryRun, boolean allowProtected,
                            List<String> paths, Object... inputs) throws Exception {
        return run(new Remove(), new Remove.Params(paths, recurse, force, verbose, dryRun, allowProtected, null), inputs);
    }

    // --- mkdir ---

    @Test
    void mkdirCreatesAndEmitsTheFolder() throws Exception {
        List<Object> out = mkdir(false, false, "docs");
        assertThat(work.resolve("docs")).isDirectory();
        assertThat(out).singleElement().isInstanceOfSatisfying(FileEntry.class, e -> assertThat(e.dir()).isTrue());
    }

    @Test
    void mkdirParentsAndExistingFolders() throws Exception {
        mkdir(false, false, "a/b");
        assertThat(context.errors).singleElement().asString().startsWith("parent folder missing");
        context.errors.clear();
        mkdir(true, false, "a/b", "a/b");
        assertThat(work.resolve("a/b")).isDirectory();
        assertThat(context.errors).isEmpty();
        mkdir(false, false, "a");
        assertThat(context.errors).singleElement().asString().startsWith("already exists");
    }

    @Test
    void mkdirDryRunCreatesNothing() throws Exception {
        List<Object> out = mkdir(true, true, "x/y");
        assertThat(work.resolve("x")).doesNotExist();
        assertThat(out).containsExactly(new FileOperation("mkdir", work.resolve("x/y"), null));
    }

    // --- touch ---

    @Test
    void touchCreatesOrUpdatesFiles() throws Exception {
        Path old = file("old.txt", "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        List<Object> out = run(new Touch(), new Touch.Params(args("new.txt", "old.txt", "missing/z.txt"), false, null));
        assertThat(work.resolve("new.txt")).isEmptyFile();
        assertThat(Files.getLastModifiedTime(old).toInstant()).isAfter(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(Files.readString(old)).isEqualTo("x");
        assertThat(out).hasSize(2);
        assertThat(context.errors).singleElement().asString().startsWith("parent folder missing");
    }

    @Test
    void touchWithAWildcardUpdatesTheMatches() throws Exception {
        file("a.java", "");
        file("b.java", "");
        List<Object> out = run(new Touch(), new Touch.Params(args("*.java"), false, null));
        assertThat(out).hasSize(2);
        try (var names = Files.list(work)) { // no file named "*.java" (an invalid name on Windows anyway)
            assertThat(names.map(f -> f.getFileName().toString())).containsExactlyInAnyOrder("a.java", "b.java");
        }
    }

    // --- cp ---

    @Test
    void cpCopiesAFileAndRefusesToOverwrite() throws Exception {
        file("a.txt", "one");
        cp(false, false, false, false, args("a.txt", "b.txt"));
        assertThat(work.resolve("b.txt")).hasContent("one");
        file("a.txt", "two");
        cp(false, false, false, false, args("a.txt", "b.txt"));
        assertThat(work.resolve("b.txt")).hasContent("one");
        assertThat(context.errors).singleElement().asString().contains("use -f to overwrite");
        context.errors.clear();
        List<Object> out = cp(false, true, true, false, args("a.txt", "b.txt"));
        assertThat(work.resolve("b.txt")).hasContent("two");
        assertThat(out).singleElement().isInstanceOf(FileEntry.class);
    }

    @Test
    void cpCopiesSeveralSourcesIntoAFolder() throws Exception {
        file("a.txt", "a");
        file("b.txt", "b");
        Files.createDirectories(work.resolve("backup"));
        cp(false, false, false, false, args("*.txt", "backup"));
        assertThat(work.resolve("backup/a.txt")).hasContent("a");
        assertThat(work.resolve("backup/b.txt")).hasContent("b");
        cp(false, false, false, false, args("a.txt", "b.txt", "missing"));
        assertThat(context.errors).singleElement().asString().startsWith("destination is not a folder");
    }

    @Test
    void cpFoldersNeedRecurseAndCannotGoIntoThemselves() throws Exception {
        file("src/x.txt", "x");
        file("src/sub/y.txt", "y");
        cp(false, false, false, false, args("src", "copy"));
        assertThat(work.resolve("copy")).doesNotExist();
        assertThat(context.errors).singleElement().asString().startsWith("is a folder");
        context.errors.clear();
        cp(true, false, false, false, args("src", "copy"));
        assertThat(work.resolve("copy/sub/y.txt")).hasContent("y");
        cp(true, false, false, false, args("src", "src/sub/deeper"));
        assertThat(context.errors).singleElement().asString().startsWith("cannot copy or move a folder into itself");
    }

    @Test
    void cpTakesItsSourcesFromThePipeline() throws Exception {
        Path a = file("a.txt", "a");
        Files.createDirectories(work.resolve("out"));
        var entry = new FileEntry("a.txt", 1, Instant.EPOCH, a, false, "txt");
        cp(false, false, false, false, args("out"), entry, "a.txt", a);
        assertThat(work.resolve("out/a.txt")).hasContent("a");
        assertThat(context.errors).hasSize(2).allSatisfy(e -> assertThat(e).startsWith("already exists"));
    }

    @Test
    void cpDryRunCopiesNothing() throws Exception {
        file("a.txt", "a");
        List<Object> out = cp(false, false, false, true, args("a.txt", "b.txt"));
        assertThat(work.resolve("b.txt")).doesNotExist();
        assertThat(out).containsExactly(new FileOperation("copy", work.resolve("a.txt"), work.resolve("b.txt")));
    }

    @Test
    void cpCopiesALinkAsALink() throws Exception {
        Path target = file("target.txt", "t");
        Path link;
        try {
            link = Files.createSymbolicLink(work.resolve("link"), target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links not available");
            return;
        }
        cp(false, false, false, false, args("link", "copy"));
        assertThat(Files.isSymbolicLink(work.resolve("copy"))).isTrue();
        assertThat(Files.readSymbolicLink(work.resolve("copy"))).isEqualTo(Files.readSymbolicLink(link));
    }

    @Test
    void cpNeedsASourceAndADestination() {
        assertThatThrownBy(() -> cp(false, false, false, false, args())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source and destination expected");
        assertThatThrownBy(() -> cp(false, false, false, false, args("a.txt")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- mv ---

    @Test
    void mvRenamesAndMovesIntoAFolder() throws Exception {
        file("a.txt", "a");
        file("dir/x.txt", "x");
        mv(false, false, "a.txt", "b.txt");
        assertThat(work.resolve("a.txt")).doesNotExist();
        assertThat(work.resolve("b.txt")).hasContent("a");
        mv(false, false, "b.txt", "dir");
        assertThat(work.resolve("dir/b.txt")).hasContent("a");
        mv(false, false, "dir", "moved");
        assertThat(work.resolve("moved/x.txt")).hasContent("x");
        assertThat(context.emitted).hasSize(3); // -v: one entry per move
        assertThat(context.errors).isEmpty();
    }

    @Test
    void mvRefusesToOverwriteAndToMoveProtectedPaths() throws Exception {
        file("a.txt", "a");
        file("b.txt", "b");
        mv(false, false, "a.txt", "b.txt");
        assertThat(work.resolve("a.txt")).exists();
        assertThat(context.errors).singleElement().asString().startsWith("already exists");
        context.errors.clear();
        file("keep/k.txt", "k");
        mv(false, false, "keep", "elsewhere");
        assertThat(work.resolve("keep")).exists();
        assertThat(context.errors).singleElement().asString().startsWith("protected path");
        context.errors.clear();
        mv(false, true, "keep", "elsewhere");
        assertThat(work.resolve("elsewhere/k.txt")).exists();
        assertThat(context.errors).isEmpty();
    }

    // --- rm ---

    @Test
    void rmRemovesFilesAndReportsMissingOnes() throws Exception {
        file("a.tmp", "");
        file("b.tmp", "");
        rm(false, false, false, false, false, args("*.tmp", "absent.txt"));
        assertThat(work.resolve("a.tmp")).doesNotExist();
        assertThat(work.resolve("b.tmp")).doesNotExist();
        assertThat(context.errors).singleElement().asString().startsWith("not found");
        context.errors.clear();
        rm(false, true, false, false, false, args("absent.txt"));
        assertThat(context.errors).isEmpty();
    }

    @Test
    void rmNeedsRecurseForANonEmptyFolder() throws Exception {
        file("full/x.txt", "x");
        Files.createDirectories(work.resolve("empty"));
        rm(false, false, false, false, false, args("full", "empty"));
        assertThat(work.resolve("full/x.txt")).exists();
        assertThat(work.resolve("empty")).doesNotExist();
        assertThat(context.errors).singleElement().asString().startsWith("folder not empty");
        context.errors.clear();
        List<Object> out = rm(true, false, true, false, false, args("full"));
        assertThat(work.resolve("full")).doesNotExist();
        assertThat(out).extracting(o -> ((FileEntry) o).name()).containsExactly("x.txt", "full");
    }

    @Test
    void rmDoesNotFollowLinks() throws Exception {
        Path kept = file("outside/kept.txt", "k");
        Path link;
        try {
            link = Files.createSymbolicLink(work.resolve("link"), kept.getParent());
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links not available");
            return;
        }
        rm(true, false, false, false, false, args("link"));
        assertThat(link).doesNotExist();
        assertThat(kept).hasContent("k");
    }

    @Test
    void rmRefusesProtectedPathsUnlessAllowed() throws Exception {
        file("keep/k.txt", "k");
        rm(true, false, false, false, false, args("keep"));
        rm(true, false, false, false, false, args("."));
        assertThat(work.resolve("keep/k.txt")).exists();
        assertThat(context.errors).hasSize(2);
        assertThat(context.errors.getFirst()).startsWith("protected path").contains("--allow-protected");
        assertThat(context.errors.get(1)).contains("--allow-protected"); // "." contains keep, and is the current folder
        context.errors.clear();
        rm(true, false, false, false, true, args("keep"));
        assertThat(work.resolve("keep")).doesNotExist();
        assertThat(context.errors).isEmpty();
    }

    @Test
    void rmDryRunListsWhatWouldBeRemoved() throws Exception {
        file("d/x.txt", "x");
        List<Object> out = rm(true, false, false, true, false, args("d"));
        assertThat(work.resolve("d/x.txt")).exists();
        assertThat(out).containsExactly(new FileOperation("remove", work.resolve("d/x.txt"), null),
                new FileOperation("remove", work.resolve("d"), null));
    }

    @Test
    void rmTakesItsPathsFromThePipeline() throws Exception {
        Path a = file("a.tmp", "");
        var entry = new FileEntry("a.tmp", 0, Instant.EPOCH, a, false, "tmp");
        rm(false, false, false, false, false, args(), entry, 42);
        assertThat(a).doesNotExist();
        assertThat(context.errors).singleElement().asString().startsWith("not a file");
    }

    @Test
    void rmNeedsAPath() {
        assertThatThrownBy(() -> rm(false, false, false, false, false, args()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path expected");
    }
}
