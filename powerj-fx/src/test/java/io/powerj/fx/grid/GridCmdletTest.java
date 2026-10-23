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
import io.powerj.fx.model.GridModel;
import io.powerj.fx.runtime.FxUnavailableException;

class GridCmdletTest {

    private record Person(String name, int age) {
    }

    private record Fragile(String name, int broken) {
        public int broken() {
            throw new IllegalStateException("cannot read broken");
        }
    }

    /** Remembers what the cmdlet asked to show. */
    private static final class RecordingOpener implements WindowOpener {
        final List<String> titles = new ArrayList<>();
        final List<GridModel> models = new ArrayList<>();

        @Override
        public void open(String title, GridModel model) {
            titles.add(title);
            models.add(model);
        }
    }

    private static void run(GridCmdlet grid, TestContext<Void> context, Object... inputs) {
        GridParams params = new GridParams();
        grid.begin(params, context);
        for (Object input : inputs) {
            grid.process(params, input, context);
        }
        grid.end(params, context);
    }

    @Test
    void opensOneWindowWithTheObjectsAsATable() {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), new TestContext<>(), new Person("Ada", 36), new Person("Alan", 41));
        assertThat(opener.titles).containsExactly("PowerJ grid");
        GridModel model = opener.models.getFirst();
        assertThat(model.columns()).containsExactly(new ColumnSpec("name", ColumnKind.TEXT),
                new ColumnSpec("age", ColumnKind.NUMBER));
        assertThat(model.rows()).hasSize(2);
        assertThat(model.rows().get(1).values()).containsExactly(Map.entry("name", "Alan"), Map.entry("age", 41));
    }

    @Test
    void objectsOfSeveralTypesShareTheTable() {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), new TestContext<>(), new Person("Ada", 36), "text", Map.of("k", 1));
        GridModel model = opener.models.getFirst();
        assertThat(model.columns().stream().map(ColumnSpec::name)).containsExactly("name", "age", "value", "k");
        assertThat(model.rows()).hasSize(3);
    }

    @Test
    void emptyInputStillOpensTheWindow() {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), new TestContext<>());
        assertThat(opener.models.getFirst().rows()).isEmpty();
        assertThat(opener.models.getFirst().columns()).isEmpty();
    }

    @Test
    void emitsNothing() {
        TestContext<Void> context = new TestContext<>();
        run(new GridCmdlet(new RecordingOpener()), context, new Person("Ada", 36));
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).isEmpty();
    }

    @Test
    void aPropertyThatCannotBeReadIsReportedOncePerColumn() {
        TestContext<Void> context = new TestContext<>();
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), context, new Fragile("a", 1), new Fragile("b", 2), new Fragile("c", 3));
        assertThat(context.errors).containsExactly("cannot read the property broken: cannot read broken");
        assertThat(opener.models.getFirst().rows()).hasSize(3);
        assertThat(opener.models.getFirst().rows().get(2).get("name")).isEqualTo("c");
        assertThat(opener.models.getFirst().rows().get(2).get("broken")).isNull();
    }

    @Test
    void theErrorOfAnExecutionIsReportedAgainInTheNext() {
        TestContext<Void> first = new TestContext<>();
        TestContext<Void> second = new TestContext<>();
        GridCmdlet grid = new GridCmdlet(new RecordingOpener());
        run(grid, first, new Fragile("a", 1));
        run(grid, second, new Fragile("a", 1));
        assertThat(first.errors).hasSize(1);
        assertThat(second.errors).hasSize(1);
    }

    @Test
    void refusesToRunAtTheHeadOfAPipeline() {
        TestContext<Void> context = new TestContext<>();
        context.hasInput = false;
        assertThatThrownBy(() -> new GridCmdlet(new RecordingOpener()).begin(new GridParams(), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("grid expects objects from a pipeline (ls | grid)");
    }

    @Test
    void anUnavailableToolkitIsAUsageError() {
        WindowOpener failing = (title, model) -> {
            throw new FxUnavailableException("no display", new RuntimeException("x"));
        };
        assertThatThrownBy(() -> run(new GridCmdlet(failing), new TestContext<>(), "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no display")
                .hasCauseInstanceOf(FxUnavailableException.class);
    }

    @Test
    void executionsDoNotShareTheirRows() {
        RecordingOpener opener = new RecordingOpener();
        GridCmdlet grid = new GridCmdlet(opener);
        TestContext<Void> first = new TestContext<>();
        TestContext<Void> second = new TestContext<>();
        GridParams params = new GridParams();
        grid.begin(params, first);
        grid.begin(params, second);
        grid.process(params, "a", first);
        grid.process(params, "b", second);
        grid.process(params, "c", second);
        grid.end(params, second);
        grid.end(params, first);
        assertThat(opener.models.get(0).rows()).hasSize(2);
        assertThat(opener.models.get(1).rows()).hasSize(1);
    }

    @Test
    void isDeclaredAsAGraphicalCmdletThatIsNeverPreviewed() {
        CmdletInfo info = GridCmdlet.class.getAnnotation(CmdletInfo.class);
        assertThat(info.name()).isEqualTo("grid");
        assertThat(info.category()).isEqualTo("Graphical");
        assertThat(info.preview()).isFalse();
    }
}
