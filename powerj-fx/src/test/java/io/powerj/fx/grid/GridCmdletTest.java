package io.powerj.fx.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.CmdletInfo;
import io.powerj.fx.runtime.FxUnavailableException;

class GridCmdletTest {

    /** Remembers what the cmdlet asked to show. */
    private static final class RecordingOpener implements WindowOpener {
        final List<String> titles = new ArrayList<>();
        final List<String> texts = new ArrayList<>();

        @Override
        public void open(String title, String text) {
            titles.add(title);
            texts.add(text);
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
    void opensOneWindowWithTheNumberOfObjects() {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), new TestContext<>(), "a", 2, new Object());
        assertThat(opener.titles).containsExactly("PowerJ grid");
        assertThat(opener.texts).containsExactly("3 objects received");
    }

    @Test
    void singularForOneObject() {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), new TestContext<>(), "a");
        assertThat(opener.texts).containsExactly("1 object received");
    }

    @Test
    void emptyInputStillOpensTheWindow() {
        RecordingOpener opener = new RecordingOpener();
        run(new GridCmdlet(opener), new TestContext<>());
        assertThat(opener.texts).containsExactly("0 objects received");
    }

    @Test
    void emitsNothing() {
        TestContext<Void> context = new TestContext<>();
        run(new GridCmdlet(new RecordingOpener()), context, "a");
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).isEmpty();
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
        WindowOpener failing = (title, text) -> {
            throw new FxUnavailableException("no display", new RuntimeException("x"));
        };
        assertThatThrownBy(() -> run(new GridCmdlet(failing), new TestContext<>(), "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no display")
                .hasCauseInstanceOf(FxUnavailableException.class);
    }

    @Test
    void executionsDoNotShareTheirCount() {
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
        assertThat(opener.texts).containsExactly("2 objects received", "1 object received");
    }

    @Test
    void isDeclaredAsAGraphicalCmdletThatIsNeverPreviewed() {
        CmdletInfo info = GridCmdlet.class.getAnnotation(CmdletInfo.class);
        assertThat(info.name()).isEqualTo("grid");
        assertThat(info.category()).isEqualTo("Graphical");
        assertThat(info.preview()).isFalse();
    }
}
