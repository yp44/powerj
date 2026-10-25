package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.powerj.api.ScriptBlock;

/**
 * Defects of the architecture review that a test demonstrates (docs/REVUE-ARCHITECTURE.md, §4). A test marked
 * {@code @Disabled} is red today: it states the expected behaviour and names the phase of the plan that fixes it, where
 * the annotation is removed. An enabled one pins a behaviour that the review had flagged but that turned out harmless.
 */
class KnownDefectsTest {

    private static ScriptBlock block(java.util.function.Function<Object, Object> function) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return function.apply(current);
            }

            @Override
            public String source() {
                return "block";
            }
        };
    }

    // --- C-16: sort compares mixed values through their text, which is not a total order ---

    @Disabled("C-16, fixed in Phase 1.6: order the values by type rank, then by value")
    @Test
    void sortComparatorIsTransitive() {
        var comparator = new SortComparator(false, _ -> { });

        // 3 < 20 (numbers), 20 < "25" (text), "25" < 3 (text): a cycle, so there is no order.
        boolean cycle = comparator.compare(3, 20) < 0 && comparator.compare(20, "25") < 0
                && comparator.compare("25", 3) < 0;

        assertThat(cycle).as("3 < 20 < \"25\" < 3").isFalse();
    }

    @Disabled("C-16, fixed in Phase 1.6: TimSort rejects an inconsistent comparator")
    @Test
    void sortingMixedValuesNeverFailsWithAContractViolation() throws Exception {
        var context = new TestContext<Object>(Path.of("."));
        context.hasInput = true;
        var sort = new Sort();
        var params = new Sort.Params(new ArrayList<>(), false, false);
        Random random = new Random(42);
        List<Object> inputs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            int n = random.nextInt(60);
            inputs.add(random.nextBoolean() ? (Object) n : String.valueOf(n));
        }

        sort.begin(params, context);
        for (Object input : inputs) {
            sort.process(params, input, context);
        }
        sort.end(params, context);
        context.closeStates();

        assertThat(context.emitted).hasSize(400);
    }

    // --- C-15: to-json takes a cancellation for an object it cannot write ---

    @Disabled("C-15, fixed in Phase 1.6: ToJson must not catch IllegalStateException around code that is cancellable")
    @Test
    void toJsonDoesNotSwallowACancellation() {
        var context = new TestContext<String>(Path.of("."));
        context.hasInput = true;
        var toJson = new ToJson();
        var params = new ToJson.Params(false, true, null);
        List<Integer> big = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            big.add(i);
        }

        toJson.begin(params, context);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> toJson.process(params, big, context)).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    // --- C-01: where kept its state in a map keyed by the (value-compared) parameters ---

    @Test
    void twoWhereStagesWithEqualParametersAreIndependent() throws Exception {
        var even = block(value -> ((Integer) value) % 2 == 0);
        var params = new Where.Params(List.of(even)); // equal for both stages: the same record, the same block
        var where = new Where();
        var first = new TestContext<Object>(Path.of("."));
        var second = new TestContext<Object>(Path.of("."));

        where.begin(params, first);
        where.begin(params, second);
        where.process(params, 1, first);
        where.process(params, 2, first);
        where.end(params, first);
        first.closeStates();
        where.process(params, 3, second);
        where.process(params, 4, second);
        where.end(params, second);
        second.closeStates();

        // The collision of the old table was masked by a computeIfAbsent, so the result was right; it is now
        // impossible: each execution holds its own state.
        assertThat(first.emitted).containsExactly(2);
        assertThat(second.emitted).containsExactly(4);
    }
}
