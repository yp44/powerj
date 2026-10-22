package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.BlockStat;
import io.powerj.api.JvmStat;
import io.powerj.api.LineStat;
import io.powerj.api.NativeStat;
import io.powerj.api.PerfReport;
import io.powerj.api.PhaseStat;
import io.powerj.api.StageStat;

class PerfTest {

    private final TestContext<Object> context = new TestContext<>(Path.of("."));

    private static PerfReport report(int line, String text) {
        var ms = Duration.ofMillis(line);
        return new PerfReport(line, text, Duration.ofMillis(10L * line), line == 3,
                List.of(new PhaseStat(line, "parse", ms), new PhaseStat(line, "execute", ms)),
                List.of(new StageStat(line, 1, "ls", null, 5L, ms, ms, ms, ms, true),
                        new StageStat(line, 2, "count", 5L, 1L, ms, ms, ms, ms, false)),
                List.of(new BlockStat(line, 2, "$_.size > 1", 5, ms, ms, ms, ms)),
                List.of(new NativeStat(line, "git status", 42, ms, 0)),
                new JvmStat(line, 1, ms, 1024, 3, ms));
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
        run(new Perf.Params(null, null, false));
        assertThat(context.emitted).hasSize(2).allSatisfy(o -> assertThat(o).isInstanceOf(StageStat.class));
        assertThat(context.emitted).extracting(o -> ((StageStat) o).line()).containsOnly(3);
    }

    @Test
    void aLineNumberCountsBackFromTheLast() throws Exception {
        keep(3);
        run(new Perf.Params(2, null, false));
        assertThat(context.emitted).extracting(o -> ((StageStat) o).line()).containsOnly(2);
    }

    @Test
    void allLinesOldestFirst() throws Exception {
        keep(3);
        run(new Perf.Params(null, null, true));
        assertThat(context.emitted).extracting(o -> ((StageStat) o).line()).containsExactly(1, 1, 2, 2, 3, 3);
    }

    @Test
    void eachViewEmitsItsRecords() throws Exception {
        keep(1);
        for (var view : Perf.View.values()) {
            context.emitted.clear();
            run(new Perf.Params(null, view, false));
            Class<?> expected = switch (view) {
                case STAGES -> StageStat.class;
                case BLOCKS -> BlockStat.class;
                case NATIVES -> NativeStat.class;
                case PHASES -> PhaseStat.class;
                case JVM -> JvmStat.class;
                case LINES -> LineStat.class;
            };
            assertThat(context.emitted).isNotEmpty().allSatisfy(o -> assertThat(o).isInstanceOf(expected));
        }
    }

    @Test
    void linesListsEveryKeptLineUnlessOneIsAsked() throws Exception {
        keep(3);
        run(new Perf.Params(null, Perf.View.LINES, false));
        assertThat(context.emitted).extracting(o -> ((LineStat) o).text()).containsExactly("line 1", "line 2", "line 3");
        var third = (LineStat) context.emitted.get(2);
        assertThat(third.cancelled()).isTrue();
        assertThat(third.bottleneck()).isEqualTo("ls");

        context.emitted.clear();
        run(new Perf.Params(2, Perf.View.LINES, false));
        assertThat(context.emitted).extracting(o -> ((LineStat) o).text()).containsExactly("line 2");
    }

    @Test
    void jvmGivesOneRowPerLine() throws Exception {
        keep(2);
        run(new Perf.Params(null, Perf.View.JVM, true));
        assertThat(context.emitted).hasSize(2);
    }

    @Test
    void nothingMeasuredIsANonBlockingMessage() throws Exception {
        run(new Perf.Params(null, null, false));
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).hasSize(1);
        assertThat(context.errors.getFirst()).contains("Alt+P");
    }

    @Test
    void aLineBeyondTheKeptOnesIsReported() throws Exception {
        keep(2);
        run(new Perf.Params(5, null, false));
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).hasSize(1).first().asString().contains("5").contains("2");
    }

    @Test
    void invalidArgumentsAreBlockingErrors() {
        keep(2);
        assertThatThrownBy(() -> run(new Perf.Params(0, null, false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(new Perf.Params(1, null, true))).isInstanceOf(IllegalArgumentException.class);
    }
}
