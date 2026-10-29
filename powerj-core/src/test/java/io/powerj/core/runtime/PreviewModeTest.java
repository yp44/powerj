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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.error.PjException;
import io.powerj.core.lang.ParseContext;
import io.powerj.core.lang.Parser;
import io.powerj.core.nativeexec.CommandResolver;
import io.powerj.core.support.PreviewableItems;

/** What the preview decides by itself, on top of the executor it drives (PreviewTest covers it end to end). */
@Timeout(30)
class PreviewModeTest {

    @TempDir
    Path tmp;

    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<PreviewSettings> settings = new AtomicReference<>(PreviewSettings.defaults());
    private PreviewMode preview;
    private PipelineExecutor executor;
    private ParseContext parseContext;
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
        parseContext = new ParseContext(_ -> false, _ -> false);
        var evaluator = new Evaluator(session, _ -> {
            throw new UnsupportedOperationException("no subexpression in these tests");
        }, () -> parseContext);
        executor = new PipelineExecutor(session, io, resolution, new NativeRunner(), evaluator,
                new ErrorReporter(session, io), () -> ExecutionObserver.NONE);
        preview = new PreviewMode(() -> parseContext, executor, settings::get);
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

    private void limitStagesTo(int limit) {
        settings.set(new PreviewSettings(PreviewSettings.DEFAULT_DEADLINE_MILLIS, limit));
    }

    /** Runs the line like the shell's time limit does: the calling thread is interrupted after {@code millis}. */
    private PreviewResult runInterruptedAfter(String line, long millis) {
        Thread caller = Thread.currentThread();
        Thread watch = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(millis);
                caller.interrupt();
            } catch (InterruptedException _) {
                // the preview ended first
            }
        });
        try {
            return run(line, 10);
        } finally {
            watch.interrupt();
            Thread.interrupted();
        }
    }

    @Test
    void aStageSendsOnlyTheLimitToTheNextOneAndTheValuesAreSaidToBePartial() {
        limitStagesTo(20);

        assertThat(run("items -n 500 | tally", 10)).isInstanceOfSatisfying(PreviewResult.Values.class, values -> {
            assertThat(values.values()).containsExactly(20); // tally counted what it received: the first 20
            assertThat(values.partial()).isTrue();
            assertThat(values.more()).isFalse();
        });
    }

    @Test
    void aStageThatSendsExactlyTheLimitIsNotCut() {
        limitStagesTo(20);

        assertThat(run("items -n 20 | tally", 10)).isInstanceOfSatisfying(PreviewResult.Values.class, values -> {
            assertThat(values.values()).containsExactly(20);
            assertThat(values.partial()).isFalse();
        });
    }

    @Test
    void theLastStageIsNotCutByTheLimitOfTheStages() {
        limitStagesTo(20);

        assertThat(run("items -n 500", 100)).isInstanceOfSatisfying(PreviewResult.Values.class, values -> {
            assertThat(values.values()).hasSize(100);
            assertThat(values.partial()).isFalse();
        });
    }

    @Test
    void everyStageButTheLastIsCut() {
        limitStagesTo(20);

        assertThat(run("items -n 500 | tally | tally", 10)).isInstanceOfSatisfying(PreviewResult.Values.class,
                values -> {
                    assertThat(values.values()).containsExactly(1); // the second tally received one count
                    assertThat(values.partial()).isTrue();
                });
    }

    @Test
    void theLimitIsReadAtEachPreview() {
        limitStagesTo(10);
        assertThat(run("items -n 500 | tally", 10)).isInstanceOfSatisfying(PreviewResult.Values.class,
                values -> assertThat(values.values()).containsExactly(10));

        limitStagesTo(30);
        assertThat(run("items -n 500 | tally", 10)).isInstanceOfSatisfying(PreviewResult.Values.class,
                values -> assertThat(values.values()).containsExactly(30));
    }

    @Test
    void theLimitOfTheStagesOnlyCutsAPreview() throws Exception {
        limitStagesTo(20);
        List<Object> values = new ArrayList<>();

        var pipeline = Parser.parse("items -n 500 | tally", parseContext).steps().getFirst().statement().pipeline();
        executor.execute(pipeline, values::add, true, Redirections.NONE);

        assertThat(values).containsExactly(500);
    }

    @Test
    void aPreviewThatTheTimeLimitStoppedBeforeAnyValueIsTimedOutNotEmpty() {
        assertThat(runInterruptedAfter("stall | tally", 300)).isInstanceOf(PreviewResult.TimedOut.class);
    }

    @Test
    void aPreviewThatTheTimeLimitStoppedAfterSomeValuesShowsThem() {
        assertThat(runInterruptedAfter("stall", 300)).isInstanceOfSatisfying(PreviewResult.Values.class, values -> {
            assertThat(values.values()).hasSize(3);
            assertThat(values.more()).isTrue();
        });
    }

    @Test
    void anInterruptionThatTheEvaluationGivesBackInsteadOfThrowingIsStillATimeout() {
        // `nothing` never checks for cancellation: it ends normally and the thread is still interrupted, as when the
        // time limit lands while the pipeline waits for its stages (they are joined without being interrupted)
        Thread.currentThread().interrupt();
        PreviewResult result;
        try {
            result = run("nothing", 10);
        } finally {
            Thread.interrupted();
        }
        assertThat(result).isInstanceOf(PreviewResult.TimedOut.class);
    }

    @Test
    void aLineWithNothingToShowIsAnEmptyResultNotATimeout() {
        assertThat(run("nothing", 10)).isInstanceOfSatisfying(PreviewResult.Values.class, values -> {
            assertThat(values.values()).isEmpty();
            assertThat(values.partial()).isFalse();
        });
    }
}
