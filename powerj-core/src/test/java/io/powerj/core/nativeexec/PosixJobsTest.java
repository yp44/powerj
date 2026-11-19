package io.powerj.core.nativeexec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.nativeexec.BackgroundLauncher.Output;
import io.powerj.core.nativeexec.BackgroundLauncher.Stage;
import io.powerj.core.support.Waits;

/** A background job on Linux and macOS, below the interpreter (issue #128). */
@EnabledOnOs({OS.LINUX, OS.MAC})
class PosixJobsTest {

    @TempDir
    Path tmp;

    @Test
    void aStageThatCannotStartKillsTheStagesAlreadyStartedAndLeavesNoneBehind() {
        assumeTrue(BackgroundLauncher.available());
        Set<Long> before = children();
        Path sleep = Files.isExecutable(Path.of("/usr/bin/sleep")) ? Path.of("/usr/bin/sleep") : Path.of("/bin/sleep");

        assertThatThrownBy(() -> BackgroundLauncher.start(
                List.of(new Stage(sleep, List.of("30"), false), new Stage(tmp.resolve("no-such-program"), List.of(), false)),
                tmp, Map.of("PATH", "/usr/bin:/bin"), Output.discard(), Output.discard()))
                .isInstanceOf(IOException.class);

        // The first stage is killed, then reaped: no child of ours, running or zombie, is left from this job.
        Waits.until("the stage started first to be gone", () -> children().stream().allMatch(before::contains));
    }

    private static Set<Long> children() {
        return ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
    }

    /** SIGTERM went to the pid of the leader: what the shell script had started in the background survived (#173, S-07). */
    @Test
    void stoppingAJobStopsTheProcessesItsLeaderStartedToo() throws Exception {
        assumeTrue(BackgroundLauncher.available());
        Path pidFile = tmp.resolve("grandchild.pid");

        List<Process> job = BackgroundLauncher.start(
                List.of(new Stage(Path.of("/bin/sh"), List.of("-c", "sleep 100 & echo $! > " + pidFile + "; wait"), false)),
                tmp, Map.of("PATH", "/usr/bin:/bin"), Output.discard(), Output.discard());
        Waits.until("the leader has started its background process", () -> Files.isReadable(pidFile)
                && !readPid(pidFile).isBlank());
        String grandchild = readPid(pidFile);
        assertThat(running(grandchild)).isTrue();

        job.getFirst().destroy();

        Waits.until("the grandchild to stop", () -> !running(grandchild));
    }

    private static String readPid(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /** {@code true} if the process runs: a zombie, which nobody has reaped yet, has stopped. */
    private static boolean running(String pid) {
        try {
            Process ps = new ProcessBuilder("ps", "-o", "stat=", "-p", pid).redirectErrorStream(true).start();
            String state = new String(ps.getInputStream().readAllBytes()).strip();
            ps.waitFor();
            return !state.isEmpty() && !state.startsWith("Z");
        } catch (IOException | InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
