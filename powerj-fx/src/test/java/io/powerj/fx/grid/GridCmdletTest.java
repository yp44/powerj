package io.powerj.fx.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
        final List<LiveTable> lives = new ArrayList<>();
        final List<Integer> pendingAtOpening = new ArrayList<>();

        @Override
        public void open(String title, LiveTable live) {
            titles.add(title);
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

    private static void run(GridCmdlet grid, GridParams params, TestContext<Void> context, Object... inputs)
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
        TestContext<Void> context = new TestContext<>();
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
        TestContext<Void> context = new TestContext<>();
        run(new GridCmdlet(new RecordingOpener()), NO_OPTIONS, context, new Person("Ada", 36));
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).isEmpty();
        assertThat(context.stopInputCalls).isZero();
    }

    // --- --max-rows ---

    @Test
    void readingStopsAtTheLimitAndTheTableIsTruncated() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        TestContext<Void> context = new TestContext<>();
        run(new GridCmdlet(opener), new GridParams(3), context, "a", "b", "c", "d", "e", "f");
        assertThat(opener.show().total()).isEqualTo(3);
        assertThat(context.stopInputCalls).isEqualTo(1);
        assertThat(opener.live().state()).isEqualTo(LiveTable.State.TRUNCATED);
    }

    @Test
    void exactlyTheLimitIsNotTruncated() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        TestContext<Void> context = new TestContext<>();
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
        TestContext<Void> context = new TestContext<>();
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
        TestContext<Void> context = new TestContext<>();
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
        TestContext<Void> context = new TestContext<>();
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
        WindowOpener failing = (title, live) -> {
            throw new FxUnavailableException("no display", new RuntimeException("x"));
        };
        assertThatThrownBy(() -> run(new GridCmdlet(failing), NO_OPTIONS, new TestContext<>(), "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no display")
                .hasCauseInstanceOf(FxUnavailableException.class);
        assertThatThrownBy(() -> run(new GridCmdlet(failing), NO_OPTIONS, new TestContext<>()))
                .isInstanceOf(IllegalArgumentException.class); // an empty input opens it at the end
    }

    // --- errors, usage ---

    @Test
    void aPropertyThatCannotBeReadIsReportedOncePerColumn() throws Exception {
        TestContext<Void> context = new TestContext<>();
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
        TestContext<Void> first = new TestContext<>();
        TestContext<Void> second = new TestContext<>();
        GridCmdlet grid = new GridCmdlet(new RecordingOpener());
        run(grid, NO_OPTIONS, first, new Fragile("a", 1));
        run(grid, NO_OPTIONS, second, new Fragile("a", 1));
        assertThat(first.errors).hasSize(1);
        assertThat(second.errors).hasSize(1);
    }

    @Test
    void refusesToRunAtTheHeadOfAPipeline() {
        TestContext<Void> context = new TestContext<>();
        context.hasInput = false;
        assertThatThrownBy(() -> new GridCmdlet(new RecordingOpener()).begin(NO_OPTIONS, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("grid expects objects from a pipeline (ls | grid)");
    }

    @Test
    void executionsDoNotShareTheirRows() throws Exception {
        RecordingOpener opener = new RecordingOpener();
        GridCmdlet grid = new GridCmdlet(opener);
        TestContext<Void> first = new TestContext<>();
        TestContext<Void> second = new TestContext<>();
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
