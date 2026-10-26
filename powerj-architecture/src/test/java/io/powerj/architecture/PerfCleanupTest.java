package io.powerj.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * The profiler's recordings are temporary: when the shell ends, the folder that holds them goes with them. That is done
 * by one hook at exit (one {@code deleteOnExit} per line would remember every line of a long session), so it is tested
 * the only way it can be: from outside, once the process has ended.
 */
class PerfCleanupTest {

    @Test
    void theRecordingsFolderIsRemovedWhenTheShellEnds() throws Exception {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                "-Dpowerj.language=en", "--enable-native-access=ALL-UNNAMED", PerfCleanupProbe.class.getName())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the probe did not end: " + output);
        }
        String reported = output.lines().filter(line -> line.startsWith(PerfCleanupProbe.RECORDING)).findFirst()
                .orElseThrow(() -> new AssertionError("the probe did not report a recording: " + output))
                .substring(PerfCleanupProbe.RECORDING.length());
        boolean profilerInThisRuntime = ModuleLayer.boot().findModule("jdk.jfr").isPresent();
        assumeTrue(profilerInThisRuntime, "this runtime has no profiler, so there is no recording");
        // On the class path the profiler is found through META-INF/services: without it there would be nothing to clean.
        assertThat(reported).as("the profiler was not found: %s", output).isNotEqualTo("none");

        Path recording = Path.of(reported);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(recording.getFileName().toString()).startsWith("line-").endsWith(".jfr");
        assertThat(recording.getParent().getFileName().toString()).startsWith("powerj-perf-");
        assertThat(Files.exists(recording)).as("the recording, left behind: %s", recording).isFalse();
        assertThat(Files.exists(recording.getParent())).as("the folder, left behind: %s", recording.getParent())
                .isFalse();
    }
}
