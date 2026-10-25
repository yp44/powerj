package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.error.PjException;
import io.powerj.core.nativeexec.NativeRunner;
import io.powerj.core.perf.PerfRecorder;
import io.powerj.core.support.FakeCmdlets;

/** Where the values of a statement are written: the terminal, or the file of a redirection. */
class StatementOutputTest {

    @TempDir
    Path tmp;

    private final StringWriter terminal = new StringWriter();
    private ShellIo io;

    @BeforeEach
    void setUp() {
        io = new ShellIo(new PrintWriter(terminal, true), _ -> { }, false, () -> 80);
    }

    private static Optional<NativeRunner.FileTarget> to(Path file, boolean append) {
        return Optional.of(new NativeRunner.FileTarget(file, append));
    }

    @Test
    void valuesGoToTheTerminalByDefault() {
        try (var output = new StatementOutput(io, Optional.empty(), ExecutionObserver.NONE)) {
            output.accept("hello");
        }

        assertThat(terminal.toString()).contains("hello");
    }

    @Test
    void valuesGoToTheFileOfARedirection() throws IOException {
        Path file = tmp.resolve("out.txt");

        try (var output = new StatementOutput(io, to(file, false), ExecutionObserver.NONE)) {
            output.accept("hello");
        }

        assertThat(Files.readString(file)).contains("hello");
        assertThat(terminal.toString()).isEmpty();
    }

    @Test
    void theFileIsOnlyOpenedAtTheFirstValue() {
        Path file = tmp.resolve("out.txt");

        try (var _ = new StatementOutput(io, to(file, false), ExecutionObserver.NONE)) {
            assertThat(file).doesNotExist(); // a redirected native command writes it itself
        }

        assertThat(file).doesNotExist();
    }

    @Test
    void anAppendedFileKeepsWhatWasThere() throws IOException {
        Path file = tmp.resolve("out.txt");
        Files.writeString(file, "before" + System.lineSeparator());

        try (var output = new StatementOutput(io, to(file, true), ExecutionObserver.NONE)) {
            output.accept("after");
        }

        assertThat(Files.readString(file)).startsWith("before").contains("after");
    }

    @Test
    void aFileIsReplacedUnlessItIsAppended() throws IOException {
        Path file = tmp.resolve("out.txt");
        Files.writeString(file, "before");

        try (var output = new StatementOutput(io, to(file, false), ExecutionObserver.NONE)) {
            output.accept("after");
        }

        assertThat(Files.readString(file)).doesNotContain("before").contains("after");
    }

    @Test
    void aTableInAFileIsNotTruncatedToTheWidthOfTheTerminal() throws IOException {
        Path file = tmp.resolve("out.txt");
        String wide = "x".repeat(300);

        try (var output = new StatementOutput(io, to(file, false), ExecutionObserver.NONE)) {
            output.accept(new FakeCmdlets.Item(wide, 1));
        }

        assertThat(Files.readString(file)).contains(wide);
    }

    @Test
    void aTableOnTheTerminalIsTruncatedToItsWidth() {
        String wide = "x".repeat(300);

        try (var output = new StatementOutput(io, Optional.empty(), ExecutionObserver.NONE)) {
            output.accept(new FakeCmdlets.Item(wide, 1));
        }

        assertThat(terminal.toString()).doesNotContain(wide);
    }

    @Test
    void anUnwritableFileIsReported() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("folder"));

        try (var output = new StatementOutput(io, to(folder, false), ExecutionObserver.NONE)) {
            assertThatThrownBy(() -> output.accept("hello")).isInstanceOf(PjException.class)
                    .hasMessageContaining("cannot write to");
        }
    }

    @Test
    void theTimeSpentDisplayingIsGivenToTheMeasureOfTheLine() {
        var perf = new PerfRecorder("line", System.nanoTime(), 0);

        try (var output = new StatementOutput(io, Optional.empty(), perf)) {
            // a table is laid out when it ends, which is in close(): there is something to measure
            for (int i = 1; i <= 50; i++) {
                output.accept(new FakeCmdlets.Item("item" + i, i));
            }
        }

        assertThat(perf.finish(1, false).phases()).anySatisfy(phase -> {
            assertThat(phase.name()).isEqualTo("display");
            assertThat(phase.time()).isPositive();
        });
    }
}
