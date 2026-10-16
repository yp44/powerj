package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;

/** Preview mode (FR-66): what runs, what stops the preview, and that nothing changes. */
@Timeout(30)
class PreviewTest {

    public record Item(String name, int size) { }

    @CmdletInfo(name = "items", preview = true, summary = "Produces test objects")
    static final class Items implements Cmdlet<Items.Params, Void, Item> {

        record Params(@Option(shortName = 'n') int count, @Option boolean forever) { }

        @Override
        public void begin(Params params, CmdletContext<Item> context) {
            for (int i = 1; params.forever() || i <= (params.count() == 0 ? 3 : params.count()); i++) {
                context.emit(new Item("item" + i, i * 10));
            }
        }
    }

    @CmdletInfo(name = "keep", preview = true, summary = "Filters with a block")
    static final class Keep implements Cmdlet<Keep.Params, Object, Object> {

        record Params(@Option(position = 0) ScriptBlock condition) { }

        @Override
        public void process(Params params, Object input, CmdletContext<Object> context) {
            boolean kept;
            try {
                kept = params.condition().test(input);
            } catch (java.util.concurrent.CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                context.error(e.getMessage());
                return;
            }
            if (kept) {
                context.emit(input);
            }
        }
    }

    /** Writes a file: not declared previewable. */
    @CmdletInfo(name = "write", summary = "Writes a file")
    static final class Write implements Cmdlet<Write.Params, Void, String> {

        record Params(@Option(position = 0) String file) { }

        @Override
        public void begin(Params params, CmdletContext<String> context) throws Exception {
            Files.writeString(context.currentDirectory().resolve(params.file()), "x");
        }
    }

    /** Previewable only without {@code --set}. */
    @CmdletInfo(name = "setting", preview = true, summary = "Shows or changes a setting")
    static final class Setting implements Cmdlet<Setting.Params, Void, String> {

        record Params(@Option String set) { }

        @Override
        public boolean previewable(Params params) {
            return params.set() == null;
        }

        @Override
        public void begin(Params params, CmdletContext<String> context) {
            if (params.set() != null) {
                context.environment().put("SETTING", params.set());
            }
            context.emit(context.environment().getOrDefault("SETTING", "none"));
        }
    }

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        session = new Session(tmp, tmp, new java.util.HashMap<>(System.getenv()));
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of(new Items(), new Keep(), new Write(), new Setting())));
    }

    private PreviewResult preview(String line) {
        return interpreter.preview(line, 10, 120, false);
    }

    private List<Object> values(String line) {
        PreviewResult result = preview(line);
        assertThat(result).isInstanceOf(PreviewResult.Values.class);
        return ((PreviewResult.Values) result).values();
    }

    private String off(String line) {
        PreviewResult result = preview(line);
        assertThat(result).isInstanceOf(PreviewResult.Off.class);
        return ((PreviewResult.Off) result).reason();
    }

    /** {@code C:\...} in a PowerJ string: backslashes escaped. */
    private static String quoted(Path file) {
        return "\"" + file.toString().replace("\\", "\\\\") + "\"";
    }

    @Test
    void previewableCmdletsAndBlocksRun() {
        assertThat(values("items")).containsExactly(new Item("item1", 10), new Item("item2", 20), new Item("item3", 30));
        assertThat(values("items -n 5 | keep { i -> i.name.endsWith(\"2\") }")).containsExactly(new Item("item2", 20));
        assertThat(values("items | keep { $_.size > 10 }")).hasSize(2);
    }

    @Test
    void stopsAfterTheLimitEvenOnAnEndlessCmdlet() {
        var result = (PreviewResult.Values) preview("items --forever");
        assertThat(result.values()).hasSize(10);
        assertThat(result.more()).isTrue();
        assertThat(((PreviewResult.Values) preview("items -n 10")).more()).isFalse();
    }

    @Test
    void expressionsAndPureJavaCallsRun() {
        assertThat(values("\"abc\".toUpperCase()")).containsExactly("ABC");
        assertThat(values("java.time.LocalDate.of(2026, 1, 1).plusDays(10).dayOfWeek")).containsExactly(DayOfWeek.SUNDAY);
        assertThat(values("java.util.List.of(\"b\", \"a\").stream().sorted().toList()")).containsExactly("a", "b");
        assertThat(values("java.nio.file.Path.of(\"a\", \"b.txt\").fileName")).containsExactly(Path.of("b.txt"));
        assertThat(values("Math.max(3, 7)")).containsExactly(7);
    }

    @Test
    void cmdletNotDeclaredPreviewableStopsThePreview() {
        assertThat(off("write out.txt")).contains("write");
        assertThat(tmp.resolve("out.txt")).doesNotExist();
    }

    @Test
    void optionsThatChangeSomethingStopThePreview() {
        assertThat(values("setting")).containsExactly("none");
        assertThat(off("setting --set on")).contains("setting");
        assertThat(session.environment()).doesNotContainKey("SETTING");
    }

    @Test
    void builtinCommandsStopThePreview() {
        Path before = session.currentDirectory();
        assertThat(off("cd ..")).contains("cd");
        assertThat(session.currentDirectory()).isEqualTo(before);
    }

    @Test
    void nativeCommandsStopThePreview() {
        assumeFalse(Platform.isWindows(), "sh");
        assertThat(off("sh -c \"touch native.txt\"")).contains("sh");
        assertThat(off("items | sh -c \"touch native.txt\"")).contains("sh");
        assertThat(tmp.resolve("native.txt")).doesNotExist();
    }

    @Test
    void javaCallsOutsideThePreviewSetStopThePreview() throws Exception {
        Path file = Files.writeString(tmp.resolve("keep.txt"), "x");
        assertThat(off("java.nio.file.Files.delete(java.nio.file.Path.of(" + quoted(file) + "))"))
                .contains("Files.delete");
        assertThat(off("items | keep { i -> java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("
                + quoted(file) + ")) }")).contains("Files.deleteIfExists");
        assertThat(off("new java.io.FileWriter(" + quoted(tmp.resolve("new.txt")) + ")")).contains("FileWriter");
        assertThat(off("java.util.List.of(1, 2, 3).parallelStream().map(x -> java.nio.file.Files.deleteIfExists("
                + "java.nio.file.Path.of(" + quoted(file) + "))).toList()")).contains("parallelStream");
        assertThat(file).exists();
        assertThat(tmp.resolve("new.txt")).doesNotExist();
    }

    @Test
    void aLazyValueIsDisplayedUnderTheSameChecks() throws Exception {
        Path file = Files.writeString(tmp.resolve("lazy.txt"), "x");
        // The stream inside the list is only read when the value is displayed.
        assertThat(off("java.util.List.of(java.util.stream.Stream.of(1).map(x -> java.nio.file.Files.deleteIfExists("
                + "java.nio.file.Path.of(" + quoted(file) + "))))")).contains("Files.deleteIfExists");
        assertThat(file).exists();
        // A block converted to a Java function before the preview: still checked when the preview calls it.
        interpreter.execute("$f = java.util.function.Function.identity().andThen(x -> java.nio.file.Files.deleteIfExists("
                + "java.nio.file.Path.of(" + quoted(file) + ")))");
        assertThat(errors).isEmpty();
        assertThat(off("java.util.List.of(1).stream().map($f).toList()")).contains("Files.deleteIfExists");
        assertThat(off("java.util.List.of(1).stream().parallel().map($f).toList()")).contains("parallel");
        assertThat(file).exists();
    }

    @Test
    void valuesAreFormattedLikeTheDisplay() {
        var result = (PreviewResult.Values) preview("items -n 2");
        assertThat(result.lines()).hasSize(4); // header, separator, two rows
        assertThat(result.lines().get(0)).contains("name", "size");
        assertThat(result.lines().get(3)).contains("item2", "20");
    }

    @Test
    void collectionsAreReadNotChanged() throws Exception {
        interpreter.execute("$l = new java.util.ArrayList(java.util.List.of(\"b\", \"a\"))");
        assertThat(values("$l.size()")).containsExactly(2);
        assertThat(off("$l.add(\"c\")")).contains("add");
        assertThat(off("$l.sort((a, b) -> a.compareTo(b))")).contains("sort");
        assertThat(session.variable("l")).isEqualTo(List.of("b", "a"));
    }

    @Test
    void assignmentsRedirectionsAndSeveralCommandsAreNotPreviewed() {
        assertThat(preview("$x = 1")).isInstanceOf(PreviewResult.Off.class);
        assertThat(session.variableNames()).doesNotContain("x");
        assertThat(preview("\"a\" > out.txt")).isInstanceOf(PreviewResult.Off.class);
        assertThat(tmp.resolve("out.txt")).doesNotExist();
        assertThat(preview("1; 2")).isInstanceOf(PreviewResult.Off.class);
    }

    @Test
    void exitIsNotPreviewed() {
        assertThat(off("System.exit(3)")).contains("exit");
        assertThat(session.exitRequest()).isEmpty();
    }

    @Test
    void aStreamInAVariableIsNotUsedUp() throws Exception {
        interpreter.execute("$s = java.util.stream.Stream.of(1, 2)");
        assertThat(off("$s.count()")).contains("$s");
        out.getBuffer().setLength(0);
        interpreter.execute("$s.count()");
        assertThat(out.toString().strip()).isEqualTo("2");
    }

    @Test
    void errorsAreReportedWithoutBeingDisplayed() {
        assertThat(preview("")).isInstanceOf(PreviewResult.Empty.class);
        assertThat(preview("items |")).isInstanceOf(PreviewResult.Incomplete.class);
        assertThat(preview("\"abc\".nope()")).isInstanceOf(PreviewResult.Failed.class);
        assertThat(preview("nope")).isInstanceOf(PreviewResult.Failed.class);
        assertThat(((PreviewResult.Values) preview("items | keep { i -> i.nope }")).errors()).isEqualTo(3);
        assertThat(out.toString()).isEmpty();
        assertThat(errors).isEmpty();
        assertThat(session.recentErrors()).isEmpty();
    }

    @Test
    void interruptingThePreviewReturnsWhatHasArrived() throws Exception {
        var started = new CompletableFuture<Thread>();
        var result = CompletableFuture.supplyAsync(() -> {
            started.complete(Thread.currentThread());
            return interpreter.preview("items --forever | keep { i -> false }", 10, 120, false);
        });
        Thread worker = started.get(5, TimeUnit.SECONDS);
        Thread.sleep(200);
        worker.interrupt();
        var values = (PreviewResult.Values) result.get(10, TimeUnit.SECONDS);
        assertThat(values.values()).isEmpty();
        assertThat(values.more()).isTrue();
    }
}
