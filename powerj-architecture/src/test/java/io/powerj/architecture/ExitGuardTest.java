package io.powerj.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * FR-58: Java code typed at the prompt must not be able to end the shell ({@code System.exit} is the same as the
 * {@code exit} command). Review finding C-12: the interception is only written for the direct call.
 * <p>
 * Each line runs in a child JVM (this module sees every other module on the class path), so a line that does end the JVM
 * only ends the child.
 */
class ExitGuardTest {

    private record Result(int exitCode, String output) {
        boolean survived() {
            return output.contains(GuardProbe.ALIVE);
        }
    }

    private static Result run(String line) throws IOException, InterruptedException {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                "-Dpowerj.language=en", "--enable-native-access=ALL-UNNAMED", GuardProbe.class.getName(),
                java.util.Base64.getEncoder().encodeToString(line.getBytes(StandardCharsets.UTF_8)))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(text -> !text.startsWith("Picked up ")) // JAVA_TOOL_OPTIONS of the machine, not ours
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the probe did not end: " + output);
        }
        return new Result(process.exitValue(), output);
    }

    @Test
    void theDirectCallIsTurnedIntoAnExitRequest() throws Exception {
        Result result = run("System.exit(7)");

        assertThat(result.survived()).as(result.output()).isTrue();
        assertThat(result.output()).contains("exitRequest=OptionalInt[7]");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void theRuntimeExitIsTurnedIntoAnExitRequest() throws Exception {
        Result result = run("Runtime.getRuntime().exit(5)");

        assertThat(result.output()).contains("exitRequest=OptionalInt[5]");
    }

    @Test
    void aLineThatDoesNothingDangerousRunsToTheEnd() throws Exception {
        Result result = run("Math.max(3, 7)");

        assertThat(result.survived()).isTrue();
        assertThat(result.output().lines()).startsWith("7");
        assertThat(result.output()).contains("exitRequest=OptionalInt.empty");
    }

    @Test
    void aMethodReferenceCannotEndTheShell() throws Exception {
        Result result = run("[0] | map System::exit");

        assertThat(result.survived()).as("the JVM ended with code %d; output: %s", result.exitCode(), result.output())
                .isTrue();
        assertThat(result.output()).contains("exitRequest=OptionalInt[0]");
    }

    @Test
    void reflectionCannotEndTheShell() throws Exception {
        Result result = run("Class.forName(\"java.lang.System\").getMethod(\"exit\", Integer.TYPE).invoke(null, 0)");

        assertThat(result.survived()).as("the JVM ended with code %d; output: %s", result.exitCode(), result.output())
                .isTrue();
        assertThat(result.output()).contains("exitRequest=OptionalInt[0]");
    }
}
