package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.perf.AllocStat;
import io.powerj.api.perf.BlockStat;
import io.powerj.api.perf.GcStat;
import io.powerj.api.perf.HotMethod;
import io.powerj.api.perf.JvmStat;
import io.powerj.api.perf.LineStat;
import io.powerj.api.perf.NativeStat;
import io.powerj.api.perf.PerfReport;
import io.powerj.api.perf.PhaseStat;
import io.powerj.api.perf.StageStat;

class PerfTest {

    private final TestContext<Object> context = new TestContext<>(Path.of("."));

    private static PerfReport report(int line, String text) {
        return report(line, text, null);
    }

    private static PerfReport report(int line, String text, Path recording) {
        var ms = Duration.ofMillis(line);
        return new PerfReport(line, text, Duration.ofMillis(10L * line), line == 3,
                List.of(new PhaseStat(line, "parse", ms), new PhaseStat(line, "execute", ms)),
                List.of(new StageStat(line, 1, "ls", null, 5L, ms, ms, ms, ms, true),
                        new StageStat(line, 2, "count", 5L, 1L, ms, ms, ms, ms, false)),
                List.of(new BlockStat(line, 2, "$_.size > 1", 5, ms, ms, ms, ms)),
                List.of(new NativeStat(line, "git status", 42, ms, 0)),
                new JvmStat(line, 1, ms, 1024, 3, ms),
                recording == null ? List.of() : List.of(new HotMethod(line, 1, "java.util.ArrayList.add", 7, 70.0)),
                recording == null ? List.of() : List.of(new GcStat(line, "G1Young", "Evacuation", ms)),
                recording == null ? List.of() : List.of(new AllocStat(line, 1, "byte[]", 4096)),
                recording);
    }

    /** Most recent first, as the session keeps them. */
    private void keep(int lines) {
        var reports = new java.util.ArrayList<PerfReport>();
        for (int i = lines; i >= 1; i--) {
            reports.add(report(i, "line " + i));
        }
        context.perfReports = reports;
    }

    private void run(Perf.Params params) throws Exception {
        new Perf().begin(params, context);
    }

    @Test
    void stagesOfTheLastLineByDefault() throws Exception {
        keep(3);
        run(new Perf.Params(null, null, false, null));
        assertThat(context.emitted).hasSize(2).allSatisfy(o -> assertThat(o).isInstanceOf(StageStat.class));
        assertThat(context.emitted).extracting(o -> ((StageStat) o).line()).containsOnly(3);
    }

    @Test
    void aLineNumberIsTheOneOfTheLineColumn() throws Exception {
        keep(3);
        run(new Perf.Params(1, null, false, null));
        assertThat(context.emitted).extracting(o -> ((StageStat) o).line()).containsOnly(1); // the first, not the last
        context.emitted.clear();
        run(new Perf.Params(3, null, false, null));
        assertThat(context.emitted).extracting(o -> ((StageStat) o).line()).containsOnly(3);
    }

    @Test
    void numbersDoNotGoBackWhenOldLinesAreDropped() throws Exception {
        var reports = new java.util.ArrayList<PerfReport>();
        for (int i = 60; i >= 11; i--) { // 50 lines kept out of 60 measured
            reports.add(report(i, "line " + i));
        }
        context.perfReports = reports;
        run(new Perf.Params(null, null, false, null));
        assertThat(context.emitted).extracting(o -> ((StageStat) o).line()).containsOnly(60);
        context.emitted.clear();
        run(new Perf.Params(11, null, false, null));
        assertThat(context.emitted).extracting(o -> ((StageStat) o).line()).containsOnly(11);
        context.emitted.clear();
        run(new Perf.Params(1, null, false, null)); // dropped
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).hasSize(1).first().asString().contains("line 1 is not kept").contains("11").contains("60");
    }

    @Test
    void allLinesOldestFirst() throws Exception {
        keep(3);
        run(new Perf.Params(null, null, true, null));
        assertThat(context.emitted).extracting(o -> ((StageStat) o).line()).containsExactly(1, 1, 2, 2, 3, 3);
    }

    @Test
    void eachViewEmitsItsRecords() throws Exception {
        keep(1);
        for (var view : Perf.View.values()) {
            if (view.ordinal() >= Perf.View.HOT.ordinal()) {
                continue; // the profiler's views need a recording: see below
            }
            context.emitted.clear();
            run(new Perf.Params(null, view, false, null));
            Class<?> expected = switch (view) {
                case STAGES -> StageStat.class;
                case BLOCKS -> BlockStat.class;
                case NATIVES -> NativeStat.class;
                case PHASES -> PhaseStat.class;
                case JVM -> JvmStat.class;
                case LINES -> LineStat.class;
                case HOT, GC, ALLOC -> throw new AssertionError(view);
            };
            assertThat(context.emitted).isNotEmpty().allSatisfy(o -> assertThat(o).isInstanceOf(expected));
        }
    }

    @Test
    void linesListsEveryKeptLineUnlessOneIsAsked() throws Exception {
        keep(3);
        run(new Perf.Params(null, Perf.View.LINES, false, null));
        assertThat(context.emitted).extracting(o -> ((LineStat) o).text()).containsExactly("line 1", "line 2", "line 3");
        var third = (LineStat) context.emitted.get(2);
        assertThat(third.cancelled()).isTrue();
        assertThat(third.bottleneck()).isEqualTo("ls");

        context.emitted.clear();
        run(new Perf.Params(2, Perf.View.LINES, false, null));
        assertThat(context.emitted).extracting(o -> ((LineStat) o).text()).containsExactly("line 2");
    }

    @Test
    void jvmGivesOneRowPerLine() throws Exception {
        keep(2);
        run(new Perf.Params(null, Perf.View.JVM, true, null));
        assertThat(context.emitted).hasSize(2);
    }

    @Test
    void nothingMeasuredIsANonBlockingMessage() throws Exception {
        run(new Perf.Params(null, null, false, null));
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).hasSize(1);
        assertThat(context.errors.getFirst()).contains("Alt+P");
    }

    @Test
    void aLineBeyondTheKeptOnesIsReported() throws Exception {
        keep(2);
        run(new Perf.Params(5, null, false, null));
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).hasSize(1).first().asString().contains("line 5").contains("1 to 2");
    }

    @Test
    void invalidArgumentsAreBlockingErrors() {
        keep(2);
        assertThatThrownBy(() -> run(new Perf.Params(0, null, false, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(new Perf.Params(1, null, true, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profilerViewsEmitTheRecordedData() throws Exception {
        var file = Files.createTempFile("perf", ".jfr");
        context.perfReports = List.of(report(1, "line 1", file));
        for (var view : List.of(Perf.View.HOT, Perf.View.GC, Perf.View.ALLOC)) {
            context.emitted.clear();
            run(new Perf.Params(null, view, false, null));
            assertThat(context.emitted).hasSize(1);
        }
        assertThat(context.emitted.getFirst()).isInstanceOf(AllocStat.class);
        Files.delete(file);
    }

    @Test
    void profilerViewsWithoutARecordingGiveOneMessage() throws Exception {
        keep(2);
        run(new Perf.Params(null, Perf.View.HOT, true, null));
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).hasSize(1).first().asString().contains("Flight Recorder");
    }

    @Test
    void saveCopiesTheRecordingToAFileOrIntoAFolder(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        var recording = Files.writeString(tmp.resolve("source.jfr"), "jfr");
        context.perfReports = List.of(report(4, "line 4", recording));
        var cmdlet = new Perf();
        var ctx = new TestContext<Object>(tmp);
        ctx.perfReports = context.perfReports;

        cmdlet.begin(new Perf.Params(null, null, false, Path.of("copy.jfr")), ctx);
        assertThat(tmp.resolve("copy.jfr")).hasContent("jfr");
        assertThat(ctx.emitted).containsExactly(tmp.resolve("copy.jfr"));

        Files.createDirectory(tmp.resolve("out"));
        ctx.emitted.clear();
        cmdlet.begin(new Perf.Params(null, null, false, Path.of("out")), ctx);
        assertThat(tmp.resolve("out/perf-4.jfr")).hasContent("jfr");
    }

    @Test
    void saveNeverReplacesAFileThatExists(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        var recording = Files.writeString(tmp.resolve("source.jfr"), "new recording");
        var precious = Files.writeString(tmp.resolve("precious.jfr"), "a file of the user");
        var ctx = new TestContext<Object>(tmp);
        ctx.perfReports = List.of(report(4, "line 4", recording));

        new Perf().begin(new Perf.Params(null, null, false, Path.of("precious.jfr")), ctx);

        assertThat(precious).hasContent("a file of the user");
        assertThat(ctx.emitted).isEmpty();
        assertThat(ctx.errors).singleElement().asString().contains("already exists").contains("precious.jfr");
    }

    @Test
    void saveWithoutARecordingOrWithOtherOptionsIsAnError() throws Exception {
        keep(1);
        run(new Perf.Params(null, null, false, Path.of("x.jfr")));
        assertThat(context.errors).hasSize(1).first().asString().contains("Flight Recorder");
        assertThatThrownBy(() -> run(new Perf.Params(null, Perf.View.HOT, false, Path.of("x.jfr"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(new Perf.Params(null, null, true, Path.of("x.jfr"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void savingIsNotAllowedInAPreview() {
        assertThat(new Perf().canPreview(new Perf.Params(null, null, false, null))).isTrue();
        assertThat(new Perf().canPreview(new Perf.Params(null, null, false, Path.of("x.jfr")))).isFalse();
    }
}
