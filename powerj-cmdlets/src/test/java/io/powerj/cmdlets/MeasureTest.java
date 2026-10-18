package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.powerj.api.Row;
import io.powerj.api.ScriptBlock;

class MeasureTest {

    private final TestContext<Row> context = new TestContext<>(Path.of("."));

    {
        context.hasInput = true;
        context.compiler = source -> switch (source) {
            case "$_.size" -> block(source, f -> ((FileEntry) f).size());
            case "$_.modified" -> block(source, f -> ((FileEntry) f).modified());
            default -> throw new IllegalArgumentException(source);
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

    private Row measure(Object key, Object... inputs) {
        var measure = new Measure();
        var params = new Measure.Params(key);
        measure.begin(params, context);
        for (Object input : inputs) {
            measure.process(params, input, context);
        }
        measure.end(params, context);
        assertThat(context.emitted).hasSize(1);
        return context.emitted.getFirst();
    }

    private static FileEntry file(String name, long size, long seconds) {
        return new FileEntry(name, size, Instant.ofEpochSecond(seconds), Path.of("/tmp", name), false,
                FileEntry.extensionOf(name, false));
    }

    @Test
    void integers() {
        Row row = measure(null, 3, 1, 2L);
        assertThat(row.keySet()).containsExactly("count", "sum", "average", "min", "max");
        assertThat(row).containsEntry("count", 3L).containsEntry("sum", 6L).containsEntry("average", 2.0)
                .containsEntry("min", 1).containsEntry("max", 3);
        assertThat(row.sizeColumns()).isEmpty();
    }

    @Test
    void aDecimalValueGivesADoubleSum() {
        Row row = measure(null, 1, 2.5, 3L);
        assertThat(row).containsEntry("sum", 6.5).containsEntry("max", 3L);
        assertThat((double) row.get("average")).isCloseTo(2.1667, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void sumsBeyondTheRangeOfALongStayExact() {
        Row row = measure(null, Long.MAX_VALUE, 2L);
        assertThat(row.get("sum")).isEqualTo(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TWO));
    }

    @Test
    void nullValuesAreLeftOut() {
        Row row = measure(null, 4, null, 2);
        assertThat(row).containsEntry("count", 2L).containsEntry("sum", 6L).containsEntry("average", 3.0);
    }

    @Test
    void stringsHaveAMinAndAMaxButNoSum() {
        Row row = measure(null, "pear", "apple", "fig");
        assertThat(row).containsEntry("count", 3L).containsEntry("sum", null).containsEntry("average", null)
                .containsEntry("min", "apple").containsEntry("max", "pear");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void notANumberLeavesTheSumEmpty() {
        Row row = measure(null, 1.0, Double.NaN);
        assertThat(row).containsEntry("count", 2L).containsEntry("sum", null);
    }

    @Test
    void aSizePropertyGivesSizeColumns() {
        Row row = measure("size", file("a", 100, 1), file("b", 300, 2));
        assertThat(row).containsEntry("count", 2L).containsEntry("sum", 400L).containsEntry("average", 200.0)
                .containsEntry("min", 100L).containsEntry("max", 300L);
        assertThat(row.sizeColumns()).containsExactlyInAnyOrder("sum", "average", "min", "max");
    }

    @Test
    void datesHaveAMinAndAMax() {
        Row row = measure("modified", file("a", 1, 50), file("b", 1, 10), file("c", 1, 30));
        assertThat(row).containsEntry("min", Instant.ofEpochSecond(10)).containsEntry("max", Instant.ofEpochSecond(50))
                .containsEntry("sum", null);
        assertThat(row.sizeColumns()).isEmpty();
    }

    @Test
    void aBlockKeyIsNotASize() {
        Row row = measure(block("$_.size", f -> ((FileEntry) f).size()), file("a", 100, 1));
        assertThat(row).containsEntry("sum", 100L);
        assertThat(row.sizeColumns()).isEmpty();
    }

    @Test
    void aFailingKeySkipsTheObject() {
        var key = block("$_.boom", v -> {
            if (v.equals(2)) {
                throw new IllegalStateException("boom");
            }
            return v;
        });
        Row row = measure(key, 1, 2, 3);
        assertThat(row).containsEntry("count", 2L).containsEntry("sum", 4L);
        assertThat(context.errors).singleElement().asString().contains("boom");
    }

    @Test
    void noValue() {
        Row row = measure(null);
        assertThat(row).containsEntry("count", 0L).containsEntry("sum", null).containsEntry("average", null)
                .containsEntry("min", null).containsEntry("max", null);
    }

    @Test
    void valuesThatCannotBeComparedAreReportedOnce() {
        Row row = measure(null, "b", 1, "a", 2);
        assertThat(row).containsEntry("count", 4L).containsEntry("sum", null);
        assertThat(context.errors).singleElement().asString().contains("cannot compare");
    }

    @Test
    void invalidKey() {
        assertThatThrownBy(() -> measure(42)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("42");
        assertThatThrownBy(() -> measure("bad!")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad!");
    }

    @Test
    void countCountsEveryObject() {
        var count = new Count();
        var params = new Count.Params();
        var counted = new TestContext<Long>(Path.of("."));
        count.begin(params, counted);
        count.process(params, "a", counted);
        count.process(params, null, counted);
        count.end(params, counted);
        assertThat(counted.emitted).containsExactly(2L);

        var empty = new TestContext<Long>(Path.of("."));
        count.begin(params, empty);
        count.end(params, empty);
        assertThat(empty.emitted).containsExactly(0L);
    }
}
