package io.powerj.api.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PerfReportTest {

    private static StageStat stage(int index, String command, boolean bottleneck) {
        return new StageStat(1, index, command, null, 10L, Duration.ofMillis(5), Duration.ZERO, Duration.ZERO,
                Duration.ofMillis(5), bottleneck);
    }

    private static PerfReport report(List<StageStat> stages) {
        return new PerfReport(1, "ls | sort", Duration.ofMillis(20), false, List.of(), stages, List.of(), List.of(),
                new JvmStat(1, 0, Duration.ZERO, 0, 0, Duration.ZERO), List.of(), List.of(), List.of(), null);
    }

    @Test
    void summaryNamesTheBottleneckStage() {
        LineStat summary = report(List.of(stage(1, "ls", false), stage(2, "sort", true))).summary();

        assertThat(summary.bottleneck()).isEqualTo("sort");
        assertThat(summary.text()).isEqualTo("ls | sort");
        assertThat(summary.total()).isEqualTo(Duration.ofMillis(20));
        assertThat(summary.cancelled()).isFalse();
    }

    @Test
    void summaryHasNoBottleneckWhenNoStageIsOne() {
        assertThat(report(List.of(stage(1, "ls", false))).summary().bottleneck()).isEmpty();
        assertThat(report(List.of()).summary().bottleneck()).isEmpty();
    }

    @Test
    void listsAreCopiedDefensively() {
        List<StageStat> stages = new ArrayList<>(List.of(stage(1, "ls", false)));
        PerfReport report = report(stages);
        stages.clear();

        assertThat(report.stages()).hasSize(1);
        assertThat(report.recording()).isNull();
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThatNullPointerException().isThrownBy(() -> new PerfReport(1, null, Duration.ZERO, false, List.of(),
                List.of(), List.of(), List.of(), new JvmStat(1, 0, Duration.ZERO, 0, 0, Duration.ZERO), List.of(),
                List.of(), List.of(), null)).withMessage("text");
        assertThatNullPointerException().isThrownBy(() -> new StageStat(1, 1, null, null, null, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, false)).withMessage("command");
    }
}
