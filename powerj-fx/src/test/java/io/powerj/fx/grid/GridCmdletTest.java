package io.powerj.fx.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.powerj.fx.Threads;

import io.powerj.api.CmdletInfo;
import io.powerj.fx.model.ColumnKind;
import io.powerj.fx.model.ColumnSpec;
import io.powerj.fx.model.GridRow;
import io.powerj.fx.model.GridTable;
import io.powerj.fx.model.LiveTable;
import io.powerj.fx.runtime.FxUnavailableException;

class GridCmdletTest {

    private record Person(String name, int age) {
    }

    private record Fragile(String name, int broken) {
        public int broken() {
            throw new IllegalStateException("cannot read broken");
        }
    }

    /** Remembers the windows the cmdlet asked for, and how many rows it had given them at that moment. */
    private static final class RecordingOpener implements WindowOpener {
        final List<String> titles = new ArrayList<>();
        final List<WindowOptions> optionsSeen = new ArrayList<>();
        final List<LiveTable> lives = new ArrayList<>();
        final List<Integer> pendingAtOpening = new ArrayList<>();

        @Override
        public void open(WindowOptions options, LiveTable live) {
            titles.add(options.title());
            optionsSeen.add(options);
            lives.add(live);
            pendingAtOpening.add(live.pending());
        }

        LiveTable live() {
            return lives.getFirst();
        }

        /** What the window would show: everything the pipeline gave, in order. */
        GridTable show() {
            var table = new GridTable();
            live().drain(table, Integer.MAX_VALUE);
            return table;
        }
    }

    private static final GridParams NO_OPTIONS = new GridParams(null);

    private static void run(GridCmdlet grid, GridParams params, TestContext<Object> context, Object... inputs)
            throws Exception {
        grid.begin(params, context);
        for (Object input : inputs) {
            if (context.stopInputCalls > 0) {
                break; // the runner reads no more once the cmdlet stopped its input
            }
            grid.process(params, input, context);
        }
        grid.end(params, context);
    }

    private static List<Object> names(GridTable table) {
        return table.rows().stream().map(r -> r.get("name")).toList();
    }

    @Test
    void theWindowOpensAtTheFirstObjectWhichIsAlreadyInIt() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        GridCmdlet grid = new GridCmdlet(opener);
        TestContext<Object> context = new TestContext<>();
        grid.begin(NO_OPTIONS, context);
        assertThat(opener.lives).isEmpty();                       // nothing yet: no object has arrived
        grid.process(NO_OPTIONS, new Person("Ada", 36), context);
        assertThat(opener.titles).containsExactly("PowerJ grid"); // opened as soon as there is something to show
        assertThat(opener.pendingAtOpening).containsExactly(1);
        assertThat(opener.live().state()).isEqualTo(LiveTable.State.LOADING);
        grid.process(NO_OPTIONS, new Person("Alan", 41), context);
        assertThat(opener.lives).hasSize(1);                      // one window, not one per object
        grid.end(NO_OPTIONS, context);
        assertThat(opener.live().state()).isEqualTo(LiveTable.State.COMPLETE);
        GridTable table = opener.show();
        assertThat(table.columns()).containsExactly(new ColumnSpec("name", ColumnKind.TEXT),
                new ColumnSpec("age", ColumnKind.NUMBER));
        assertThat(table.rows().stream().map(GridRow::source)).containsExactly(new Person("Ada", 36),
                new Person("Alan", 41));
    }

    @Test
    void theWindowOfACommandThatDidNotFinishIsClosedWithIt() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        GridCmdlet grid = new GridCmdlet(opener);
        TestContext<Object> context = new TestContext<>();
        grid.begin(NO_OPTIONS, context);
        grid.process(NO_OPTIONS, new Person("Ada", 36), context);
        assertThat(opener.live().isClosed()).isFalse();

        context.closeStates(); // what the shell does when the command is cancelled or fails: end() was never reached

        assertThat(opener.live().isClosed()).isTrue();
    }

    @Test
    void theWindowOfAFinishedCommandOutlivesIt() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        TestContext<Object> context = new TestContext<>();
        run(new GridCmdlet(opener), NO_OPTIONS, context, new Person("Ada", 36));

        context.closeStates(); // what the shell does when the command is over

        assertThat(opener.live().isClosed()).isFalse();
    }

    @Test
    void aCommandThatNeverOpenedAWindowHasNothingToClose() throws Exception {
        TestContext<Object> context = new TestContext<>();
        new GridCmdlet(new RecordingOpener()).begin(NO_OPTIONS, context);

        context.closeStates();
    }

    @Test
    void processNeedsBegin() {
        assertThatThrownBy(() -> new GridCmdlet(new RecordingOpener()).process(NO_OPTIONS, "x", new TestContext<>()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("begin was not called");
    }

    @Test
    void objectsOfSeveralTypesShareTheTable() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), NO_OPTIONS, new TestContext<>(), new Person("Ada", 36), "text", Map.of("k", 1));
        GridTable table = opener.show();
        assertThat(table.columns().stream().map(ColumnSpec::name)).containsExactly("name", "age", "value", "k");
        assertThat(table.total()).isEqualTo(3);
    }

    @Test
    void emptyInputOpensTheWindowAtTheEnd() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), NO_OPTIONS, new TestContext<>());
        assertThat(opener.lives).hasSize(1);
        assertThat(opener.live().state()).isEqualTo(LiveTable.State.COMPLETE);
        assertThat(opener.show().total()).isZero();
    }

    @Test
    void emitsNothing() throws Exception {
        TestContext<Object> context = new TestContext<>();
        run(new GridCmdlet(new RecordingOpener()), NO_OPTIONS, context, new Person("Ada", 36));
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).isEmpty();
        assertThat(context.stopInputCalls).isZero();
    }

    // --- --max-rows ---

    @Test
    void readingStopsAtTheLimitAndTheTableIsTruncated() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        TestContext<Object> context = new TestContext<>();
        run(new GridCmdlet(opener), new GridParams(3), context, "a", "b", "c", "d", "e", "f");
        assertThat(opener.show().total()).isEqualTo(3);
        assertThat(context.stopInputCalls).isEqualTo(1);
        assertThat(opener.live().state()).isEqualTo(LiveTable.State.TRUNCATED);
    }

    @Test
    void exactlyTheLimitIsNotTruncated() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        TestContext<Object> context = new TestContext<>();
        run(new GridCmdlet(opener), new GridParams(3), context, "a", "b", "c");
        assertThat(opener.show().total()).isEqualTo(3);
        assertThat(context.stopInputCalls).isZero();
        assertThat(opener.live().state()).isEqualTo(LiveTable.State.COMPLETE);
    }

    @Test
    void theDefaultLimitIsOneHundredThousand() {
        assertThat(NO_OPTIONS.limit()).isEqualTo(100_000);
        assertThat(new GridParams(7).limit()).isEqualTo(7);
        assertThat(GridParams.DEFAULT_MAX_ROWS).isEqualTo(100_000);
    }

    @Test
    void aLimitBelowOneIsRefused() {
        for (int bad : new int[] {0, -5}) {
            assertThatThrownBy(() -> new GridCmdlet(new RecordingOpener()).begin(new GridParams(bad), new TestContext<>()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("--max-rows must be at least 1: " + bad);
        }
    }

    @Test
    void aProcessAfterTheStopIsIgnored() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        TestContext<Object> context = new TestContext<>();
        GridCmdlet grid = new GridCmdlet(opener);
        GridParams params = new GridParams(1);
        grid.begin(params, context);
        grid.process(params, "a", context);
        grid.process(params, "b", context);   // over the limit: stops
        grid.process(params, "c", context);   // the runner would not call, but it would do nothing
        grid.end(params, context);
        assertThat(context.stopInputCalls).isEqualTo(1);
        assertThat(opener.show().total()).isEqualTo(1);
    }

    // --- the window goes away ---

    @Test
    void whenTheWindowIsClosedTheCmdletStopsReading() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        TestContext<Object> context = new TestContext<>();
        GridCmdlet grid = new GridCmdlet(opener);
        grid.begin(NO_OPTIONS, context);
        grid.process(NO_OPTIONS, "a", context);
        opener.live().close();                          // the user closes the window
        grid.process(NO_OPTIONS, "b", context);
        assertThat(context.stopInputCalls).isEqualTo(1);
        grid.process(NO_OPTIONS, "c", context);        // nothing more
        grid.end(NO_OPTIONS, context);
        assertThat(context.stopInputCalls).isEqualTo(1);
        assertThat(context.errors).isEmpty();
    }

    @Test
    void aCancelledPipelineLeavesTheWindowInterrupted() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        GridCmdlet grid = new GridCmdlet(opener);
        TestContext<Object> context = new TestContext<>();
        Thread stage = Thread.ofVirtual().start(() -> {
            try {
                grid.begin(NO_OPTIONS, context);
                grid.process(NO_OPTIONS, "a", context);
                // Ctrl+C: the stage ends, and end() is not called.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        stage.join(5_000);
        assertThat(opener.live().state()).isEqualTo(LiveTable.State.INTERRUPTED);
        assertThat(opener.show().total()).isEqualTo(1); // what arrived is shown
    }

    @Test
    void aWindowThatCannotOpenIsAUsageError() {
        WindowOpener failing = (options, live) -> {
            throw new FxUnavailableException("no display", new RuntimeException("x"));
        };
        assertThatThrownBy(() -> run(new GridCmdlet(failing), NO_OPTIONS, new TestContext<>(), "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no display")
                .hasCauseInstanceOf(FxUnavailableException.class);
        assertThatThrownBy(() -> run(new GridCmdlet(failing), NO_OPTIONS, new TestContext<>()))
                .isInstanceOf(IllegalArgumentException.class); // an empty input opens it at the end
    }

    // --- title, columns, wait, settings ---

    private static GridParams params(String title, String columns, boolean waitClose) {
        return new GridParams(null, title, columns, waitClose, false, false, false);
    }

    private record Process(String name, @io.powerj.api.Bytes long memory) {
    }

    @Test
    void rawShowsTheRealValuesWithoutUnits() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        GridParams raw = new GridParams(null, null, null, false, false, false, true);
        run(new GridCmdlet(opener), raw, new TestContext<>(), new Process("a", 14_520),
                java.time.Duration.ofMillis(1310));
        GridTable table = opener.show();
        assertThat(table.columns()).extracting(ColumnSpec::kind).contains(ColumnKind.NUMBER).doesNotContain(ColumnKind.SIZE);
        run(new GridCmdlet(opener), NO_OPTIONS, new TestContext<>(), new Process("a", 14_520));
        assertThat(opener.lives.get(1)).isNotNull();
        GridTable plain = new GridTable();
        opener.lives.get(1).drain(plain, Integer.MAX_VALUE);
        assertThat(plain.columns()).extracting(ColumnSpec::kind).contains(ColumnKind.SIZE);
    }

    @Test
    void theWindowHasATitleOfItsOwnOrTheDefaultOne() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), params("Files", null, false), new TestContext<>(), "a");
        run(new GridCmdlet(opener), params(null, null, false), new TestContext<>(), "a");
        run(new GridCmdlet(opener), params("  ", null, false), new TestContext<>(), "a");
        assertThat(opener.titles).containsExactly("Files", "PowerJ grid", "PowerJ grid");
    }

    @Test
    void theColumnsAskedForAreGivenToTheWindow() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), params(null, "age, name", false), new TestContext<>(), new Person("Ada", 36));
        assertThat(opener.optionsSeen.getFirst().columns().names()).containsExactly("age", "name");
        run(new GridCmdlet(opener), NO_OPTIONS, new TestContext<>(), new Person("Ada", 36));
        assertThat(opener.optionsSeen.get(1).columns().isAll()).isTrue();
    }

    @Test
    void aListWithoutColumnNameIsRefused() {
        for (String bad : new String[] {"", " ", ",", " , ,"}) {
            assertThatThrownBy(() -> new GridCmdlet(new RecordingOpener()).begin(params(null, bad, false),
                    new TestContext<>())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("--columns needs at least one column name: " + bad);
        }
    }

    @Test
    void aColumnAskedForThatNeverArrivedIsReportedAtTheEnd() throws Exception {
        TestContext<Object> context = new TestContext<>();
        run(new GridCmdlet(new RecordingOpener()), params(null, "name,Nothing,AGE,other", false), context,
                new Person("Ada", 36));
        assertThat(context.errors).containsExactly("no such column: Nothing, other");
    }

    @Test
    void aColumnThatArrivesLaterIsNotReported() throws Exception {
        TestContext<Object> context = new TestContext<>();
        run(new GridCmdlet(new RecordingOpener()), params(null, "name,extra", false), context, new Person("Ada", 36),
                Map.of("extra", 1));
        assertThat(context.errors).isEmpty();
    }

    @Test
    void theWindowSettingsAreInTheConfigurationFolder() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        TestContext<Object> context = new TestContext<>();
        context.configDirectory = java.nio.file.Path.of("home");
        run(new GridCmdlet(opener), NO_OPTIONS, context, "a");
        assertThat(opener.optionsSeen.getFirst().settings()).isEqualTo(java.nio.file.Path.of("home", "fx-grid.properties"));
        run(new GridCmdlet(opener), NO_OPTIONS, new TestContext<>(), "a");
        assertThat(opener.optionsSeen.get(1).settings()).isNull();
    }

    @Test
    void waitBlocksUntilTheWindowIsClosed() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        GridCmdlet grid = new GridCmdlet(opener);
        TestContext<Object> context = new TestContext<>();
        var done = new java.util.concurrent.CompletableFuture<Void>();
        Thread stage = Thread.ofVirtual().start(() -> {
            try {
                run(grid, params(null, null, true), context, "a", "b");
                done.complete(null);
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        });
        Threads.awaitParked(stage);
        assertThat(done).isNotDone();                       // the prompt is not given back
        assertThat(opener.live().state()).isEqualTo(LiveTable.State.COMPLETE);
        opener.live().close();                              // the user closes the window
        done.get(5, java.util.concurrent.TimeUnit.SECONDS);
        stage.join(5_000);
    }

    @Test
    void withoutWaitThePromptIsGivenBackAtOnce() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), params(null, null, false), new TestContext<>(), "a");
        assertThat(opener.live().isClosed()).isFalse();
    }

    @Test
    void ctrlCWhileWaitingClosesTheWindow() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread stage = new Thread(() -> {
            try {
                run(new GridCmdlet(opener), params(null, null, true), new TestContext<>(), "a");
            } catch (Exception e) {
                failure.set(e);
            }
        });
        stage.start();
        Threads.awaitParked(stage);
        stage.interrupt();                                  // Ctrl+C
        stage.join(5_000);
        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        assertThat(opener.live().isClosed()).isTrue();      // the window follows
    }

    // --- --pass-thru ---

    private static final GridParams PASS_THRU = new GridParams(null, null, null, false, true, false, false);

    /** Runs {@code grid --pass-thru} on a thread; the test plays the user with the {@link LiveTable} of the window. */
    private static final class PassThru {
        final RecordingOpener opener = new RecordingOpener();
        final TestContext<Object> context = new TestContext<>();
        final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        Thread stage;

        PassThru(GridParams params, Object... inputs) {
            stage = Thread.ofVirtual().start(() -> {
                try {
                    run(new GridCmdlet(opener), params, context, inputs);
                    done.complete(null);
                } catch (Exception e) {
                    done.completeExceptionally(e);
                }
            });
        }

        /** The window is there and the input has been read. */
        void windowShown() throws Exception {
            for (int i = 0; i < 100 && (opener.lives.isEmpty()
                    || opener.live().state() == LiveTable.State.LOADING); i++) {
                Thread.sleep(20);
            }
        }

        /** What the user ticked, by position in the table: OK. */
        void ok(int... positions) throws Exception {
            windowShown();
            GridTable table = opener.show();
            var rows = new ArrayList<GridRow>();
            for (int position : positions) {
                rows.add(table.rows().get(position));
            }
            opener.live().accept(rows);
            done.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void okGivesTheTickedObjectsBackToThePipeline() throws Exception {
        var ada = new Person("Ada", 36);
        var alan = new Person("Alan", 41);
        var grace = new Person("Grace", 85);
        PassThru run = new PassThru(PASS_THRU, ada, alan, grace);
        run.ok(2, 0);
        assertThat(run.context.emitted).containsExactly(grace, ada);        // the objects themselves, in the order given
        assertThat(run.context.emitted.get(0)).isSameAs(grace);
    }

    @Test
    void passThruWaitsForTheWindowLikeWait() throws Exception {
        PassThru run = new PassThru(PASS_THRU, "a", "b");
        run.windowShown();
        Threads.awaitParked(run.stage);
        assertThat(run.done).isNotDone();                 // the prompt is not given back
        assertThat(run.context.emitted).isEmpty();
        run.ok(1);
        assertThat(run.context.emitted).containsExactly("b");
    }

    @Test
    void closingTheWindowAnyOtherWayGivesNothing() throws Exception {
        PassThru run = new PassThru(PASS_THRU, "a", "b");
        run.windowShown();
        run.opener.live().close();                        // Cancel, the cross, Esc
        run.done.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(run.context.emitted).isEmpty();
        assertThat(run.context.errors).isEmpty();
    }

    @Test
    void okWithNothingTickedGivesNothing() throws Exception {
        PassThru run = new PassThru(PASS_THRU, "a", "b");
        run.ok();
        assertThat(run.context.emitted).isEmpty();
    }

    @Test
    void ctrlCWhileThePassThruWindowIsOpenGivesNothing() throws Exception {
        PassThru run = new PassThru(PASS_THRU, "a", "b");
        run.windowShown();
        run.stage.interrupt();
        assertThat(run.done).failsWithin(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(run.context.emitted).isEmpty();
        assertThat(run.opener.live().isClosed()).isTrue();
    }

    @Test
    void theWindowIsToldToTickRowsAndWhetherOnlyOneCanBe() throws Exception {
        PassThru plain = new PassThru(PASS_THRU, "a");
        plain.ok();
        assertThat(plain.opener.optionsSeen.getFirst().passThru()).isTrue();
        assertThat(plain.opener.optionsSeen.getFirst().single()).isFalse();
        PassThru single = new PassThru(new GridParams(null, null, null, false, true, true, false), "a");
        single.ok();
        assertThat(single.opener.optionsSeen.getFirst().single()).isTrue();
        RecordingOpener without = new RecordingOpener();
        run(new GridCmdlet(without), NO_OPTIONS, new TestContext<>(), "a");
        assertThat(without.optionsSeen.getFirst().passThru()).isFalse();
    }

    @Test
    void singleNeedsPassThru() {
        assertThatThrownBy(() -> new GridCmdlet(new RecordingOpener()).begin(
                new GridParams(null, null, null, false, false, true, false), new TestContext<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--single only goes with --pass-thru");
    }

    @Test
    void withoutPassThruNothingIsEmittedWhateverIsDone() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        TestContext<Object> context = new TestContext<>();
        run(new GridCmdlet(opener), NO_OPTIONS, context, "a", "b");
        opener.live().accept(List.of(opener.show().rows().get(0)));
        assertThat(context.emitted).isEmpty();
    }

    @Test
    void aTruncatedTableGivesWhatWasTicked() throws Exception {
        PassThru run = new PassThru(new GridParams(2, null, null, false, true, false, false), "a", "b", "c", "d");
        run.ok(1);
        assertThat(run.context.emitted).containsExactly("b");
        assertThat(run.opener.live().state()).isEqualTo(LiveTable.State.TRUNCATED);
    }

    @Test
    void isDeclaredToPassItsObjectsOn() {
        assertThat(GridCmdlet.class.getAnnotation(CmdletInfo.class).passThrough()).isTrue();
    }

    // --- errors, usage ---

    @Test
    void aPropertyThatCannotBeReadIsReportedOncePerColumn() throws Exception {
        TestContext<Object> context = new TestContext<>();
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), NO_OPTIONS, context, new Fragile("a", 1), new Fragile("b", 2), new Fragile("c", 3));
        assertThat(context.errors).containsExactly("cannot read the property broken: cannot read broken");
        GridTable table = opener.show();
        assertThat(table.total()).isEqualTo(3);
        assertThat(table.rows().get(2).get("name")).isEqualTo("c");
        assertThat(table.rows().get(2).get("broken")).isNull();
    }

    @Test
    void theErrorOfAnExecutionIsReportedAgainInTheNext() throws Exception {
        TestContext<Object> first = new TestContext<>();
        TestContext<Object> second = new TestContext<>();
        GridCmdlet grid = new GridCmdlet(new RecordingOpener());
        run(grid, NO_OPTIONS, first, new Fragile("a", 1));
        run(grid, NO_OPTIONS, second, new Fragile("a", 1));
        assertThat(first.errors).hasSize(1);
        assertThat(second.errors).hasSize(1);
    }

    @Test
    void refusesToRunAtTheHeadOfAPipeline() {
        TestContext<Object> context = new TestContext<>();
        context.hasInput = false;
        assertThatThrownBy(() -> new GridCmdlet(new RecordingOpener()).begin(NO_OPTIONS, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("grid expects objects from a pipeline (ls | grid)");
    }

    @Test
    void executionsDoNotShareTheirRows() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        GridCmdlet grid = new GridCmdlet(opener);
        TestContext<Object> first = new TestContext<>();
        TestContext<Object> second = new TestContext<>();
        grid.begin(NO_OPTIONS, first);
        grid.begin(NO_OPTIONS, second);
        grid.process(NO_OPTIONS, "a", first);
        grid.process(NO_OPTIONS, "b", second);
        grid.process(NO_OPTIONS, "c", second);
        grid.end(NO_OPTIONS, second);
        grid.end(NO_OPTIONS, first);
        assertThat(opener.lives).hasSize(2);
        var one = new GridTable();
        var two = new GridTable();
        opener.lives.get(0).drain(one, 10);
        opener.lives.get(1).drain(two, 10);
        assertThat(two.total()).isEqualTo(2);
        assertThat(one.total()).isEqualTo(1);
    }

    @Test
    void isDeclaredAsAGraphicalCmdletThatIsNeverPreviewed() {
        CmdletInfo info = GridCmdlet.class.getAnnotation(CmdletInfo.class);
        assertThat(info.name()).isEqualTo("grid");
        assertThat(info.category()).isEqualTo("Graphical");
        assertThat(info.preview()).isFalse();
    }
}
