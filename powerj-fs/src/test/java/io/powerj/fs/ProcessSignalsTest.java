package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;

/** {@link ProcessSignals}: {@code SIGCONT} reaches a stopped process (issue #128). Unix only: the programs are {@code sleep}, {@code ps}. */
class ProcessSignalsTest {

    @Test
    void resumeContinuesAStoppedProcess() throws Exception {
        assumeFalse(Host.windows(), "SIGSTOP and SIGCONT are Unix signals");
        Process sleeper = new ProcessBuilder("sleep", "30").start();
        try {
            assertThat(new ProcessBuilder("kill", "-STOP", String.valueOf(sleeper.pid())).start().waitFor()).isZero();
            awaitState(sleeper.pid(), true);

            assertThat(ProcessSignals.resume(sleeper.pid())).isTrue();

            awaitState(sleeper.pid(), false);
        } finally {
            sleeper.destroyForcibly();
        }
    }

    @Test
    void resumeSaysNoWhenThereIsNoSuchProcess() {
        assumeFalse(Host.windows(), "no SIGCONT on Windows");
        assertThat(ProcessSignals.resume(999_999_999L)).isFalse();
    }

    @Test
    void resumeDoesNothingOnWindows() {
        if (Host.windows()) {
            assertThat(ProcessSignals.resume(ProcessHandle.current().pid())).isFalse();
        }
    }

    /** Waits until the process is stopped ({@code T} in {@code ps}), or is not any more. */
    private static void awaitState(long pid, boolean stopped) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            Process ps = new ProcessBuilder("ps", "-o", "stat=", "-p", Long.toString(pid)).redirectErrorStream(true).start();
            String state = new String(ps.getInputStream().readAllBytes()).strip();
            ps.waitFor();
            if (state.startsWith("T") == stopped) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the process " + pid + " is '" + state + "', expected " + (stopped ? "stopped" : "running"));
            }
            Thread.sleep(20);
        }
    }
}
