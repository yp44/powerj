package io.powerj.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.ScriptBlock;
import io.powerj.api.perf.PerfReports;

/** The in-memory {@code CmdletContext} that the tests of the cmdlets share (#98). */
class TestContextTest {

    @Test
    void itCollectsTheObjectsAndTheErrors() {
        var context = new TestContext<String>(Path.of("/w"));

        context.emit("a");
        context.emit("b");
        context.error("oops");

        assertThat(context.emitted).containsExactly("a", "b");
        assertThat(context.errors).containsExactly("oops");
    }

    @Test
    void theCurrentDirectoryIsTheOneGivenOrTheDot() {
        assertThat(new TestContext<String>(Path.of("/w")).currentDirectory()).isEqualTo(Path.of("/w"));
        assertThat(new TestContext<String>().currentDirectory()).isEqualTo(Path.of("."));
    }

    @Test
    void theEnvironmentIsChangedThroughTheContextAndReadOnlyFromOutside() {
        var context = new TestContext<String>();

        context.setEnvironment("A", "1");
        assertThat(context.environment()).containsEntry("A", "1");
        assertThat(context.unsetEnvironment("A")).isTrue();
        assertThat(context.unsetEnvironment("A")).isFalse();
        context.environment.put("B", "2"); // a test prepares it directly
        assertThat(context.environment()).containsEntry("B", "2");
        assertThatThrownBy(() -> context.environment().put("C", "3")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void thereIsNoInputUnlessTheTestSaysSo() {
        var context = new TestContext<String>();

        assertThat(context.hasInput()).isFalse();
        context.hasInput = true;
        assertThat(context.hasInput()).isTrue();
        assertThat(context.cancelled()).isFalse();
        assertThat(context.variable("x")).isEmpty();
    }

    @Test
    void itCountsTheCallsToStopInput() {
        var context = new TestContext<String>();

        context.stopInput();
        context.stopInput();

        assertThat(context.stopInputCalls).isEqualTo(2);
    }

    @Test
    void theConfigurationFolderIsAbsentUnlessGiven() {
        var context = new TestContext<String>();

        assertThat(context.configDirectory()).isEmpty();
        context.configDirectory = Path.of("/conf");
        assertThat(context.configDirectory()).contains(Path.of("/conf"));
    }

    @Test
    void thePerfReportsAreTheOnlyServiceOffered() {
        var context = new TestContext<String>();

        assertThat(context.service(PerfReports.class)).hasValueSatisfying(r -> assertThat(r.reports()).isEmpty());
        context.perfReports = new ArrayList<>(List.of());
        assertThat(context.service(String.class)).isEmpty();
    }

    @Test
    void compilingIsRefusedUnlessTheTestSaysHow() {
        var context = new TestContext<String>();

        assertThatThrownBy(() -> context.compile("$_.x")).isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("$_.x");
        ScriptBlock block = new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return "seen " + current;
            }

            @Override
            public String source() {
                return "x";
            }
        };
        context.compiler = _ -> block;
        assertThat(context.compile("anything").invoke(1)).isEqualTo("seen 1");
    }

    @Test
    void theStateIsKeptByKindAndClosedInReverseOrderWhenTheExecutionIsOver() throws Exception {
        var context = new TestContext<String>();
        var order = new ArrayList<String>();
        record First(List<String> order) implements AutoCloseable {
            public void close() {
                order.add("first");
            }
        }
        record Second(List<String> order) implements AutoCloseable {
            public void close() {
                order.add("second");
            }
        }

        var first = context.state(First.class, () -> new First(order));
        context.state(Second.class, () -> new Second(order));

        assertThat(context.state(First.class, () -> new First(new ArrayList<>()))).isSameAs(first);
        context.closeStates();
        assertThat(order).containsExactly("second", "first");
        assertThat(context.state(First.class, () -> new First(order))).isNotSameAs(first);
    }

    record Process(long pid, String Name) {
    }

    @Test
    void aPropertyIsReadByItsExactName() {
        var context = new TestContext<String>();

        assertThat(context.readProperty(new Process(7, "sleep"), "pid")).isEqualTo(7L);
        assertThat(context.readProperty(new Process(7, "sleep"), "Name")).isEqualTo("sleep");
        assertThat(context.readProperty(java.util.Map.of("first name", "Yves"), "first name")).isEqualTo("Yves");
        assertThatThrownBy(() -> context.readProperty(new Process(7, "sleep"), "name"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThat(context.tryReadProperty(new Process(7, "sleep"), "uid").failed()).isTrue();
    }
}
