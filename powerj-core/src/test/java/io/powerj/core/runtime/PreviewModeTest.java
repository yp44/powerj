package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.Builtin;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.command.CommandResolution;
import io.powerj.core.error.PjException;
import io.powerj.core.lang.Syntax;
import io.powerj.core.nativeexec.CommandResolver;
import io.powerj.core.nativeexec.NativeRunner;
import io.powerj.core.support.PreviewableItems;

/** What the preview decides by itself, on top of the executor it drives (PreviewTest covers it end to end). */
@Timeout(30)
class PreviewModeTest {

    @TempDir
    Path tmp;

    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private PreviewMode preview;
    private Session session;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, Map.of());
        var io = new ShellIo(new PrintWriter(new StringWriter(), true), errors::add, false);
        Map<String, Builtin> builtins = new LinkedHashMap<>();
        builtins.put("boom", (_, _) -> {
            throw new PjException("boom");
        });
        var resolution = new CommandResolution(builtins, CmdletRegistry.of(PreviewableItems.all()),
                new CommandResolver());
        Syntax syntax = new Syntax(_ -> false, _ -> false);
        var evaluator = new Evaluator(session, _ -> {
            throw new UnsupportedOperationException("no subexpression in these tests");
        }, () -> syntax);
        var executor = new PipelineExecutor(session, io, resolution, new NativeRunner(), evaluator,
                new ErrorReporter(session, io), () -> ExecutionObserver.NONE);
        preview = new PreviewMode(() -> syntax, executor);
    }

    private PreviewResult run(String line, int limit) {
        return preview.preview(line, limit, 80, false);
    }

    @Test
    void aBlankLineHasNothingToShow() {
        assertThat(run("", 5)).isInstanceOf(PreviewResult.Empty.class);
        assertThat(run("   ", 5)).isInstanceOf(PreviewResult.Empty.class);
    }

    @Test
    void aLineThatIsNotFinishedIsIncompleteNotAnError() {
        assertThat(run("items | { $_.size >", 5)).isInstanceOf(PreviewResult.Incomplete.class);
    }

    @Test
    void severalStatementsAreNotPreviewed() {
        assertThat(run("items; items", 5)).isInstanceOfSatisfying(PreviewResult.Off.class,
                off -> assertThat(off.reason()).isEqualTo("several commands"));
    }

    @Test
    void aBackgroundCommandAnAssignmentAndARedirectionAreNotPreviewed() {
        assertThat(run("items &", 5)).isInstanceOf(PreviewResult.Off.class);
        assertThat(run("$x = items", 5)).isInstanceOf(PreviewResult.Off.class);
        assertThat(run("items > out.txt", 5)).isInstanceOf(PreviewResult.Off.class);
        assertThat(tmp.resolve("out.txt")).doesNotExist();
        assertThatThrownBy(() -> session.variable("x")).hasMessageContaining("unknown variable"); // not assigned
    }

    @Test
    void theValuesAreCutAtTheLimitAndSaidToBeMore() {
        assertThat(run("items -n 5", 3)).isInstanceOfSatisfying(PreviewResult.Values.class, values -> {
            assertThat(values.values()).hasSize(3);
            assertThat(values.more()).isTrue();
        });
    }

    @Test
    void whenEverythingFitsThereIsNoMore() {
        assertThat(run("items -n 3", 3)).isInstanceOfSatisfying(PreviewResult.Values.class, values -> {
            assertThat(values.values()).hasSize(3);
            assertThat(values.more()).isFalse();
            assertThat(values.lines()).isNotEmpty();
        });
    }

    @Test
    void aCommandThatWouldChangeSomethingTurnsThePreviewOff() {
        assertThat(run("boom", 5)).isInstanceOf(PreviewResult.Off.class); // a built-in command is never run
    }

    @Test
    void aFailureIsReportedWithoutBeingDisplayed() {
        assertThat(run("nothing-of-that-name", 5)).isInstanceOfSatisfying(PreviewResult.Failed.class,
                failed -> assertThat(failed.message()).isEqualTo("unknown command: nothing-of-that-name"));
        assertThat(errors).isEmpty();
    }

    @Test
    void aCmdletThatSwallowsTheStopStillGetsItsPreviewCutAndMarkedAsMore() {
        assertThat(run("careless", 3)).isInstanceOfSatisfying(PreviewResult.Values.class, values -> {
            assertThat(values.values()).hasSize(3);
            assertThat(values.more()).isTrue();
        });
    }
}
