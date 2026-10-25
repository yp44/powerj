package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.error.PjError;
import io.powerj.core.error.PjException;
import io.powerj.core.java.PreviewGuard;

/** Where the errors of a line go, and what is remembered about them. */
class ErrorReporterTest {

    @TempDir
    Path tmp;

    private final List<String> terminal = new ArrayList<>();
    private Session session;
    private ErrorReporter reporter;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, java.util.Map.of());
        reporter = new ErrorReporter(session, new ShellIo(new PrintWriter(new StringWriter()), terminal::add, false));
    }

    private static NativeRunner.FileTarget target(Path file, boolean append) {
        return new NativeRunner.FileTarget(file, append);
    }

    @Test
    void aMessageWithoutCauseIsShownAsIs() {
        assertThat(reporter.describe(PjError.of("plain"))).isEqualTo("plain");
        assertThat(session.recentErrors()).isEmpty();
    }

    @Test
    void theJavaExceptionIsKeptForTheErrorsVariable() {
        var cause = new IllegalStateException("boom");

        assertThat(reporter.describe(PjError.of("failed", cause))).isEqualTo("failed");

        assertThat(session.recentErrors()).containsExactly(cause);
    }

    @Test
    void theStackTraceIsAppendedInDebugMode() {
        session.setVariable("debug", true);

        String text = reporter.describe(PjError.of("failed", new IllegalStateException("boom")));

        assertThat(text).startsWith("failed" + System.lineSeparator())
                .contains("java.lang.IllegalStateException: boom").contains("at ");
    }

    @Test
    void aPreviewKeepsNothingInTheErrorsVariable() throws Exception {
        session.setVariable("debug", true);
        var guard = new PreviewGuard();

        String text = guard.call(() -> reporter.describe(PjError.of("failed", new IllegalStateException("boom"))));

        assertThat(text).isEqualTo("failed"); // no trace either: nothing was executed
        assertThat(session.recentErrors()).isEmpty();
    }

    @Test
    void theBlockingErrorIsRememberedUntilTheNextLine() {
        assertThat(reporter.hadBlocking()).isFalse();

        reporter.blocking();
        assertThat(reporter.hadBlocking()).isTrue();

        reporter.reset();
        assertThat(reporter.hadBlocking()).isFalse();
    }

    @Test
    void errorsGoToTheTerminalByDefault() {
        reporter.sink(Optional.empty()).accept("oops");

        assertThat(terminal).containsExactly("oops");
    }

    @Test
    void errorsAreAppendedToTheRedirectionFile() throws IOException {
        Path file = tmp.resolve("err.txt");
        var sink = reporter.sink(Optional.of(target(file, false)));

        sink.accept("first");
        sink.accept("second");

        // `2>` is truncated once by the statement (Redirections.withSharedErrors), then every message is appended
        assertThat(Files.readAllLines(file)).containsExactly("first", "second");
        assertThat(terminal).isEmpty();
    }

    @Test
    void aPreviewCollectsTheErrorsInsteadOfWritingThem() throws Exception {
        Path file = tmp.resolve("err.txt");
        var guard = new PreviewGuard();

        guard.call(() -> {
            reporter.sink(Optional.of(target(file, false))).accept("oops");
            return null;
        });

        assertThat(guard.errors()).isEqualTo(1);
        assertThat(file).doesNotExist();
        assertThat(terminal).isEmpty();
    }

    @Test
    void aBlockingErrorReplacesTheFileUnlessItAppends() throws IOException {
        Path file = tmp.resolve("err.txt");
        Files.writeString(file, "old" + System.lineSeparator());

        reporter.report("new", Optional.of(target(file, false)));
        assertThat(Files.readAllLines(file)).containsExactly("new");

        reporter.report("more", Optional.of(target(file, true)));
        assertThat(Files.readAllLines(file)).containsExactly("new", "more");
    }

    @Test
    void aBlockingErrorWithoutRedirectionGoesToTheTerminal() {
        reporter.report("oops", Optional.empty());

        assertThat(terminal).containsExactly("oops");
    }

    @Test
    void anUnwritableFileIsReportedNotThrownRaw() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("folder"));

        assertThatThrownBy(() -> reporter.report("oops", Optional.of(target(folder, false))))
                .isInstanceOf(PjException.class).hasMessageContaining("cannot write to");
        assertThatThrownBy(() -> reporter.sink(Optional.of(target(folder, true))).accept("oops"))
                .isInstanceOf(PjException.class).hasMessageContaining("cannot write to");
    }
}
