package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.perf.JvmStat;
import io.powerj.api.perf.PerfReport;
import io.powerj.core.nativeexec.NativeRun;

/** What the shell remembers about what happened. */
class ShellStatisticsTest {

    @TempDir
    Path tmp;

    private final ShellStatistics statistics = new ShellStatistics();

    private static PerfReport report(int line, Path recording) {
        return new PerfReport(line, "line " + line, Duration.ofMillis(line), false, List.of(), List.of(), List.of(),
                List.of(), new JvmStat(line, 0, Duration.ZERO, 0, 0, Duration.ZERO), List.of(), List.of(), List.of(),
                recording);
    }

    @Test
    void nothingHasRunAtFirst() {
        assertThat(statistics.lastNative()).isEmpty();
        assertThat(statistics.lastSucceeded()).isTrue();
        assertThat(statistics.recentErrors()).isEmpty();
        assertThat(statistics.perfReports()).isEmpty();
    }

    @Test
    void theLastNativeCommandAndTheLastResultAreKept() {
        var run = new NativeRun(Path.of("tool"), List.of("-x"), 42L, 3, Duration.ofMillis(5));

        statistics.recordNative(run);
        statistics.recordSuccess(false);

        assertThat(statistics.lastNative()).contains(run);
        assertThat(statistics.lastSucceeded()).isFalse();
    }

    @Test
    void theRecentErrorsAreKeptNewestFirstAndBounded() {
        for (int i = 1; i <= ShellStatistics.MAX_ERRORS + 5; i++) {
            statistics.recordError(new IllegalStateException("error " + i));
        }

        List<Throwable> errors = statistics.recentErrors();
        assertThat(errors).hasSize(ShellStatistics.MAX_ERRORS);
        assertThat(errors.getFirst()).hasMessage("error " + (ShellStatistics.MAX_ERRORS + 5));
        assertThat(errors.getLast()).hasMessage("error 6");
    }

    @Test
    void theErrorsReturnedAreACopy() {
        statistics.recordError(new IllegalStateException("first"));
        List<Throwable> before = statistics.recentErrors();

        statistics.recordError(new IllegalStateException("second"));

        assertThat(before).hasSize(1);
    }

    @Test
    void theLinesOfPerfModeAreNumberedFromOne() {
        assertThat(statistics.nextPerfLine()).isEqualTo(1);
        assertThat(statistics.nextPerfLine()).isEqualTo(2);
    }

    @Test
    void theReportsAreKeptNewestFirstAndBounded() {
        for (int i = 1; i <= ShellStatistics.MAX_PERF_REPORTS + 3; i++) {
            statistics.addPerfReport(report(i, null));
        }

        List<PerfReport> reports = statistics.perfReports();
        assertThat(reports).hasSize(ShellStatistics.MAX_PERF_REPORTS);
        assertThat(reports.getFirst().line()).isEqualTo(ShellStatistics.MAX_PERF_REPORTS + 3);
        assertThat(reports.getLast().line()).isEqualTo(4);
    }

    @Test
    void theRecordingOfAReportThatIsDroppedGoesWithIt() throws IOException {
        Path dropped = Files.writeString(tmp.resolve("old.jfr"), "x");
        Path kept = Files.writeString(tmp.resolve("new.jfr"), "y");
        statistics.addPerfReport(report(1, dropped));
        for (int i = 2; i <= ShellStatistics.MAX_PERF_REPORTS; i++) {
            statistics.addPerfReport(report(i, null));
        }
        assertThat(dropped).exists(); // still kept: the oldest of the fifty

        statistics.addPerfReport(report(ShellStatistics.MAX_PERF_REPORTS + 1, kept));

        assertThat(dropped).doesNotExist();
        assertThat(kept).exists();
    }

    @Test
    void aRecordingAlreadyGoneIsNotAnError() {
        statistics.addPerfReport(report(1, tmp.resolve("gone.jfr")));
        for (int i = 2; i <= ShellStatistics.MAX_PERF_REPORTS + 1; i++) {
            statistics.addPerfReport(report(i, null));
        }

        assertThat(statistics.perfReports()).hasSize(ShellStatistics.MAX_PERF_REPORTS);
    }
}
