package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.ScriptBlock;

/**
 * Ctrl+C is not an error of the object (review C-15): when the block of a cmdlet is cancelled, the cmdlet stops with the
 * cancellation, and it is neither reported as a skipped object nor swallowed. One case for each cmdlet that evaluates a
 * block for each object, next to the case of a block that merely fails, which is reported and skipped.
 */
class CancellationPropagationTest {

    private static ScriptBlock block(Function<Object, Object> body) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return body.apply(current);
            }

            @Override
            public String source() {
                return "block";
            }
        };
    }

    private static final ScriptBlock CANCELLED = block(_ -> {
        throw new CancellationException("Ctrl+C");
    });
    private static final ScriptBlock FAILING = block(_ -> {
        throw new IllegalStateException("no such property");
    });

    private <P extends Record> void run(Cmdlet<P, Object, ?> cmdlet, P params, TestContext<Object> context,
                                        Object... inputs) throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Cmdlet<P, Object, Object> typed = (Cmdlet) cmdlet;
        typed.begin(params, context);
        for (Object input : inputs) {
            typed.process(params, input, context);
        }
        typed.end(params, context);
        context.closeStates();
    }

    /** A cmdlet with the parameters that put {@code block} where it is evaluated for each object. */
    private record Case(String name, Cmdlet<?, Object, ?> cmdlet, Record params) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static List<Case> cases(ScriptBlock block) {
        return List.of(
                new Case("sort", new Sort(), new Sort.Params(List.of(block), false, false)),
                new Case("select", new Select(), new Select.Params(List.of(block))),
                new Case("measure", new Measure(), new Measure.Params(block)),
                new Case("group", new GroupCmdlet(), new GroupCmdlet.Params(List.of(block), false)),
                new Case("group (two keys)", new GroupCmdlet(), new GroupCmdlet.Params(List.of(block, block), false)),
                new Case("range (from)", new Range(), new Range.Params(block, null, null, false, false, false, false)),
                new Case("range (to)", new Range(), new Range.Params(1, block, null, false, false, false, false)),
                new Case("map", new MapCmdlet(), new MapCmdlet.Params(block)),
                new Case("where", new Where(), new Where.Params(List.of(block))));
    }

    /** Runs the cmdlet of the case over the inputs, in a context that collects what it emits and reports. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private TestContext<Object> runCase(Case c, Object... inputs) throws Exception {
        var context = new TestContext<Object>(Path.of("."));
        context.hasInput = true;
        run((Cmdlet) c.cmdlet(), c.params(), context, inputs);
        return context;
    }

    @Test
    void aCancelledBlockStopsEveryCmdlet() {
        for (Case c : cases(CANCELLED)) {
            assertThatThrownBy(() -> runCase(c, "a", "b")).as(c.name()).isInstanceOf(CancellationException.class);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aCancelledBlockIsNotReportedAsASkippedObject() throws Exception {
        for (Case c : cases(CANCELLED)) {
            var context = new TestContext<Object>(Path.of("."));
            context.hasInput = true;
            assertThatThrownBy(() -> run((Cmdlet) c.cmdlet(), c.params(), context, "a", "b"))
                    .isInstanceOf(CancellationException.class);
            assertThat(context.errors).as(c.name()).isEmpty();
        }
    }

    @Test
    void aBlockThatFailsIsReportedAndTheObjectSkipped() throws Exception {
        for (Case c : cases(FAILING)) {
            if (c.name().equals("range (to)")) {
                continue; // --to is only evaluated once the range is open
            }
            var context = runCase(c, "a", "b");
            assertThat(context.errors).as(c.name()).isNotEmpty();
        }
    }
}
