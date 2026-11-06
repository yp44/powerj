package io.powerj.core.nativeexec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.powerj.fs.Host;

/** {@link SpawnedProcess} as a {@link Process} (issue #128): its handle, and what it says about stopping. */
class SpawnedProcessTest {

    private static final Runnable NOTHING = () -> { };

    @Test
    void theHandleIsTheOneTakenWhileTheProcessRunsAndStaysValidAfterItsEnd() throws Exception {
        assumeFalse(Host.windows(), "sleep");
        Process child = new ProcessBuilder("sleep", "30").start();
        try {
            var exit = new CompletableFuture<Integer>();
            SpawnedProcess process = new SpawnedProcess((int) child.pid(), exit, NOTHING, NOTHING);
            assertThat(process.toHandle().pid()).isEqualTo(child.pid());
            assertThat(process.toHandle().isAlive()).isTrue();

            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            exit.complete(137);

            assertThat(process.waitFor()).isEqualTo(137);
            assertThat(process.toHandle().pid()).isEqualTo(child.pid());
            assertThat(process.toHandle().isAlive()).isFalse();
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void aProcessThatHadEndedBeforeItsHandleCouldBeTakenSaysSoWithTheExceptionOfTheContract() {
        SpawnedProcess process = new SpawnedProcess(999_999_999, CompletableFuture.completedFuture(0), NOTHING, NOTHING);

        assertThatThrownBy(process::toHandle).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void windowsHasNoGentleStop() {
        SpawnedProcess process = new SpawnedProcess(999_999_999, CompletableFuture.completedFuture(0), NOTHING, NOTHING);

        assertThat(process.supportsNormalTermination()).isEqualTo(!Host.windows());
    }
}
