package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;

class FirstLastTest {

    private final TestContext<Object> context = new TestContext<>(Path.of("."));

    {
        context.hasInput = true;
    }

    /** Runs the cmdlet as the shell does: no more objects once the input is stopped; returns how many were read. */
    private <P extends Record> int run(Cmdlet<P, Object, Object> cmdlet, P params, Object... inputs) throws Exception {
        cmdlet.begin(params, context);
        int read = 0;
        for (Object input : inputs) {
            if (context.stopInputCalls > 0) {
                break;
            }
            read++;
            cmdlet.process(params, input, context);
        }
        cmdlet.end(params, context);
        context.closeStates();
        return read;
    }

    @Test
    void firstEmitsTheFirstObjectsThenStopsTheInput() throws Exception {
        assertThat(run(new First(), new First.Params(2), "a", "b", "c", "d")).isEqualTo(2);
        assertThat(context.emitted).containsExactly("a", "b");
        assertThat(context.stopInputCalls).isEqualTo(1);
    }

    @Test
    void firstKeepsOneObjectByDefault() throws Exception {
        run(new First(), new First.Params(null), "a", "b");
        assertThat(context.emitted).containsExactly("a");
    }

    @Test
    void firstZeroStopsAtOnce() throws Exception {
        assertThat(run(new First(), new First.Params(0), "a", "b")).isZero();
        assertThat(context.emitted).isEmpty();
        assertThat(context.stopInputCalls).isEqualTo(1);
    }

    @Test
    void firstWithFewerObjects() throws Exception {
        run(new First(), new First.Params(5), "a", null);
        assertThat(context.emitted).containsExactly("a", null);
        assertThat(context.stopInputCalls).isZero();
    }

    @Test
    void firstIgnoresObjectsReceivedAfterTheStop() throws Exception {
        var first = new First();
        var params = new First.Params(1);
        first.begin(params, context);
        first.process(params, "a", context);
        first.process(params, "b", context); // a context without stopInput keeps sending
        first.end(params, context);
        context.closeStates();
        assertThat(context.emitted).containsExactly("a");
    }

    @Test
    void lastEmitsTheLastObjectsInOrder() throws Exception {
        run(new Last(), new Last.Params(2), "a", "b", null, "d");
        assertThat(context.emitted).containsExactly(null, "d");
        assertThat(context.stopInputCalls).isZero();
    }

    @Test
    void lastWithFewerObjectsOrZero() throws Exception {
        run(new Last(), new Last.Params(null), "a", "b");
        assertThat(context.emitted).containsExactly("b");
        context.emitted.clear();
        run(new Last(), new Last.Params(3), "a", "b");
        assertThat(context.emitted).containsExactly("a", "b");
        context.emitted.clear();
        run(new Last(), new Last.Params(0), "a", "b");
        assertThat(context.emitted).isEmpty();
    }

    @Test
    void negativeCountsAreRefused() {
        assertThatThrownBy(() -> run(new First(), new First.Params(-1))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
        assertThatThrownBy(() -> run(new Last(), new Last.Params(-2))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-2");
    }

    @Test
    void lastKeepsALargeInput() throws Exception {
        Object[] inputs = Arrays.stream(new Integer[5000]).map(x -> 1).toArray();
        run(new Last(), new Last.Params(3), inputs);
        assertThat(context.emitted).isEqualTo(List.of(1, 1, 1));
    }
}
