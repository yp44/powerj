package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.powerj.api.Collected;
import io.powerj.api.ScriptBlock;

/** {@code where}, {@code map} and {@code collect} driven directly (specification FR-36 to FR-36d). */
class WhereMapCollectTest {

    private final TestContext<Object> context = new TestContext<>(Path.of("."));
    private final List<String> compiled = new ArrayList<>();

    {
        context.hasInput = true;
        context.compiler = source -> {
            compiled.add(source);
            return block(source, current -> true);
        };
    }

    private static ScriptBlock block(String source, Function<Object, Object> function) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return function.apply(current);
            }

            @Override
            public String source() {
                return source;
            }
        };
    }

    private List<Object> where(List<Object> condition, Object... inputs) throws Exception {
        var where = new Where();
        var params = new Where.Params(condition);
        where.begin(params, context);
        for (Object input : inputs) {
            where.process(params, input, context);
        }
        where.end(params, context);
        context.closeStates();
        return context.emitted;
    }

    @Test
    void whereKeepsTheObjectsForWhichTheBlockIsTrue() throws Exception {
        var even = block("$_ % 2 == 0", n -> (Integer) n % 2 == 0);
        assertThat(where(List.of(even), 1, 2, 3, 4)).containsExactly(2, 4);
    }

    @Test
    void whereEvaluationErrorsSkipTheObject() throws Exception {
        var failing = block("$_.x", n -> {
            throw new IllegalStateException("no property x");
        });
        assertThat(where(List.of(failing), "a".repeat(50))).isEmpty();
        assertThat(context.errors).containsExactly("no property x (object skipped: " + "a".repeat(39) + "…)");

        context.errors.clear();
        var notBoolean = block("$_", n -> n);
        assertThat(where(List.of(notBoolean), "abc")).isEmpty();
        assertThat(context.errors).containsExactly("the block must return a boolean, got String abc (object skipped: abc)");
    }

    @Test
    void whereLetsACancellationThrough() {
        var cancelled = block("$_", n -> {
            throw new CancellationException();
        });
        assertThatThrownBy(() -> where(List.of(cancelled), 1)).isInstanceOf(CancellationException.class);
    }

    @Test
    void whereCompilesTheConditionWhenProcessIsCalledFirst() {
        var where = new Where();
        var params = new Where.Params(List.of("size", ">", 1));
        where.process(params, "x", context);
        assertThat(compiled).containsExactly("$_.size > 1");
        assertThat(context.emitted).containsExactly("x");
    }

    @Test
    void shortFormValuesBecomeJavaLiterals() throws Exception {
        where(List.of("size", ">", "10kb"));
        where(List.of("dir", "==", true));
        where(Arrays.asList("ext", "!=", null));
        where(List.of("size", "<=", 2.5));
        where(List.of("name", "==", "a\"b$c\\d\ne\tf"));
        assertThat(compiled).containsExactly("$_.size > 10kb", "$_.dir == true", "$_.ext != null", "$_.size <= 2.5",
                "$_.name == \"a\\\"b\\$c\\\\d\\ne\\tf\"");
    }

    @Test
    void shortFormErrors() {
        assertThatThrownBy(() -> where(List.of("1x", "==", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid property name: 1x");
        assertThatThrownBy(() -> where(List.of("name", "==", List.of())))
                .hasMessage("value not supported by the short form: ListN; use where { … }");
        assertThatThrownBy(() -> where(List.of("name", "like", "a")))
                .hasMessageStartingWith("condition expected: ");
        assertThatThrownBy(() -> where(List.of("name")))
                .hasMessageStartingWith("condition expected: ");
    }

    @Test
    void mapTransformsSkipsNullsAndReportsErrors() throws Exception {
        var map = new MapCmdlet();
        var params = new MapCmdlet.Params(block("$_ * 2", n -> n == null ? null : n instanceof Integer i && i < 0
                ? fail() : (Object) ((Integer) n * 2)));
        map.begin(params, context);
        map.process(params, 1, context);
        map.process(params, null, context);
        map.process(params, -1, context);
        assertThat(context.emitted).containsExactly(2);
        assertThat(context.errors).containsExactly("negative (object skipped: -1)");
    }

    private static Object fail() {
        throw new IllegalArgumentException("negative");
    }

    @Test
    void mapNeedsABlockAndLetsACancellationThrough() {
        var map = new MapCmdlet();
        assertThatThrownBy(() -> map.begin(new MapCmdlet.Params("name"), context))
                .hasMessage("transformation expected: map { f -> … } or map Type::method");
        var params = new MapCmdlet.Params(block("$_", n -> {
            throw new CancellationException();
        }));
        assertThatThrownBy(() -> map.process(params, 1, context)).isInstanceOf(CancellationException.class);
    }

    @Test
    void collectGathersEverythingIntoOneList() throws Exception {
        var collect = new Collect();
        var params = new Collect.Params();
        TestContext<Collected> collected = new TestContext<>(Path.of("."));
        collect.begin(params, collected);
        collect.process(params, "a", collected);
        collect.process(params, 2, collected);
        collect.end(params, collected);
        assertThat(collected.emitted).singleElement().satisfies(list -> assertThat(list).containsExactly("a", 2));

        TestContext<Collected> empty = new TestContext<>(Path.of("."));
        collect.begin(params, empty);
        collect.end(params, empty);
        assertThat(empty.emitted).singleElement().satisfies(list -> assertThat(list).isEmpty());
    }
}
