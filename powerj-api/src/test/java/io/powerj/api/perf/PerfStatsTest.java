package io.powerj.api.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** The small records of a performance report: each keeps its values and refuses a missing required one. */
class PerfStatsTest {

    private static final Duration D = Duration.ofMillis(3);

    @Test
    void blockStatKeepsItsValuesAndChecksTheRequiredOnes() {
        BlockStat stat = new BlockStat(1, 2, "x + 1", 4L, D, D, D, D);

        assertThat(stat.source()).isEqualTo("x + 1");
        assertThat(stat.calls()).isEqualTo(4L);
        assertThatNullPointerException().isThrownBy(() -> new BlockStat(1, 2, null, 4L, D, D, D, D))
                .withMessage("source");
        assertThatNullPointerException().isThrownBy(() -> new BlockStat(1, 2, "x", 4L, null, D, D, D))
                .withMessage("total");
        assertThatNullPointerException().isThrownBy(() -> new BlockStat(1, 2, "x", 4L, D, null, D, D))
                .withMessage("average");
        assertThatNullPointerException().isThrownBy(() -> new BlockStat(1, 2, "x", 4L, D, D, null, D))
                .withMessage("max");
        assertThatNullPointerException().isThrownBy(() -> new BlockStat(1, 2, "x", 4L, D, D, D, null))
                .withMessage("first");
    }

    @Test
    void gcStatKeepsItsValuesAndChecksTheRequiredOnes() {
        assertThat(new GcStat(1, "G1", "System.gc()", D).pause()).isEqualTo(D);
        assertThatNullPointerException().isThrownBy(() -> new GcStat(1, null, "c", D)).withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> new GcStat(1, "G1", null, D)).withMessage("cause");
        assertThatNullPointerException().isThrownBy(() -> new GcStat(1, "G1", "c", null)).withMessage("pause");
    }

    @Test
    void phaseStatKeepsItsValuesAndChecksTheRequiredOnes() {
        assertThat(new PhaseStat(1, "parse", D).name()).isEqualTo("parse");
        assertThatNullPointerException().isThrownBy(() -> new PhaseStat(1, null, D)).withMessage("name");
        assertThatNullPointerException().isThrownBy(() -> new PhaseStat(1, "parse", null)).withMessage("time");
    }

    @Test
    void nativeStatKeepsItsValuesAndChecksTheRequiredOnes() {
        assertThat(new NativeStat(1, "git", 42L, D, null).exitCode()).isNull();
        assertThat(new NativeStat(1, "git", 42L, D, 0).exitCode()).isZero();
        assertThatNullPointerException().isThrownBy(() -> new NativeStat(1, null, 42L, D, 0))
                .withMessage("command");
        assertThatNullPointerException().isThrownBy(() -> new NativeStat(1, "git", 42L, null, 0))
                .withMessage("wall");
    }

    @Test
    void allocStatKeepsItsValuesAndChecksTheRequiredOnes() {
        assertThat(new AllocStat(1, 2, "byte[]", 1024L).bytes()).isEqualTo(1024L);
        assertThatNullPointerException().isThrownBy(() -> new AllocStat(1, 2, null, 1024L)).withMessage("type");
    }

    @Test
    void hotMethodKeepsItsValuesAndChecksTheRequiredOnes() {
        assertThat(new HotMethod(1, 2, "A.b", 10L, 12.5).percent()).isEqualTo(12.5);
        assertThatNullPointerException().isThrownBy(() -> new HotMethod(1, 2, null, 10L, 12.5))
                .withMessage("method");
    }
}
