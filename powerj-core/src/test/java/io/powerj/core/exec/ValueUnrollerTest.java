package io.powerj.core.exec;

import static io.powerj.core.exec.ValueUnroller.Mode.BETWEEN_STAGES;
import static io.powerj.core.exec.ValueUnroller.Mode.DISPLAY;
import static io.powerj.core.exec.ValueUnroller.Mode.TARGETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.powerj.api.Collected;

/** The one rule that says what a value stands for (review C-07). */
class ValueUnrollerTest {

    private record Point(int x, int y) { }

    private static List<Object> unrolled(Object value, ValueUnroller.Mode mode) {
        List<Object> elements = new ArrayList<>();
        boolean unrolled = ValueUnroller.unroll(value, mode, elements::add);
        assertThat(unrolled).as("unrolled").isEqualTo(!elements.isEmpty() || isEmptyContainer(value, mode));
        return elements;
    }

    /** An empty container still counts as unrolled: it stands for no object, not for itself. */
    private static boolean isEmptyContainer(Object value, ValueUnroller.Mode mode) {
        return switch (value) {
            case Path _ -> false;
            case Collected<?> _ when mode == BETWEEN_STAGES -> false;
            case Iterable<?> _, java.util.stream.BaseStream<?, ?> _, Iterator<?> _ -> true;
            case Optional<?> _, OptionalInt _, OptionalLong _, OptionalDouble _ -> mode != DISPLAY;
            case Object array -> array.getClass().isArray();
            case null -> false;
        };
    }

    @Test
    void collectionsAreUnrolledInEveryMode() {
        for (var mode : ValueUnroller.Mode.values()) {
            assertThat(unrolled(List.of(1, 2), mode)).containsExactly(1, 2);
            assertThat(unrolled(new java.util.LinkedHashSet<>(List.of("a", "b")), mode)).containsExactly("a", "b");
            assertThat(unrolled(List.of(), mode)).isEmpty();
        }
    }

    @Test
    void arraysAreUnrolledEvenWhenPrimitive() {
        for (var mode : ValueUnroller.Mode.values()) {
            assertThat(unrolled(new int[] {1, 2, 3}, mode)).containsExactly(1, 2, 3);
            assertThat(unrolled(new String[] {"a", null}, mode)).containsExactly("a", null);
            assertThat(unrolled(new double[0], mode)).isEmpty();
        }
    }

    @Test
    void iteratorsAndStreamsAreUnrolled() {
        for (var mode : ValueUnroller.Mode.values()) {
            assertThat(unrolled(List.of(1, 2).iterator(), mode)).containsExactly(1, 2);
            assertThat(unrolled(Stream.of("a", "b"), mode)).containsExactly("a", "b");
            assertThat(unrolled(IntStream.range(0, 3), mode)).containsExactly(0, 1, 2);
        }
    }

    @Test
    void singleValuesAreNotUnrolled() {
        var called = new AtomicInteger();
        for (var mode : ValueUnroller.Mode.values()) {
            for (Object value : new Object[] {"text", 42, new Point(1, 2), Map.of("k", 1), Path.of("a", "b"),
                    new StringBuilder("sb"), null}) {
                assertThat(ValueUnroller.unroll(value, mode, _ -> called.incrementAndGet()))
                        .as("%s in %s", value, mode).isFalse();
            }
        }
        assertThat(called).hasValue(0);
    }

    @Test
    void aCollectedListIsPassedWholeBetweenStagesAndUnrolledElsewhere() {
        var list = new Collected<>(List.of(1, 2));

        assertThat(ValueUnroller.unroll(list, BETWEEN_STAGES, _ -> { })).isFalse();
        assertThat(unrolled(list, DISPLAY)).containsExactly(1, 2);
        assertThat(unrolled(list, TARGETS)).containsExactly(1, 2);
    }

    @Test
    void optionalsAreUnrolledBetweenStagesAndForTargetsButShownAsTheyAre() {
        for (var mode : new ValueUnroller.Mode[] {BETWEEN_STAGES, TARGETS}) {
            assertThat(unrolled(Optional.of("x"), mode)).containsExactly("x");
            assertThat(unrolled(Optional.empty(), mode)).isEmpty();
            assertThat(unrolled(OptionalInt.of(5), mode)).containsExactly(5);
            assertThat(unrolled(OptionalLong.of(6L), mode)).containsExactly(6L);
            assertThat(unrolled(OptionalDouble.of(1.5), mode)).containsExactly(1.5);
            assertThat(unrolled(OptionalInt.empty(), mode)).isEmpty();
        }
        assertThat(ValueUnroller.unroll(Optional.of("x"), DISPLAY, _ -> { })).isFalse();
        assertThat(ValueUnroller.unroll(OptionalInt.of(5), DISPLAY, _ -> { })).isFalse();
    }

    @Test
    void aStreamIsClosedOnceRead() {
        for (var mode : ValueUnroller.Mode.values()) {
            var closed = new AtomicInteger();
            ValueUnroller.unroll(Stream.of(1, 2).onClose(closed::incrementAndGet), mode, _ -> { });
            assertThat(closed).as("close() calls in %s", mode).hasValue(1);
        }
    }

    @Test
    void aStreamIsClosedEvenWhenTheConsumerFails() {
        var closed = new AtomicInteger();
        var stream = Stream.of(1, 2).onClose(closed::incrementAndGet);

        assertThatThrownBy(() -> ValueUnroller.unroll(stream, BETWEEN_STAGES, _ -> {
            throw new IllegalStateException("full");
        })).hasMessage("full");

        assertThat(closed).hasValue(1);
    }

    @Test
    void elementsAreDeliveredInOrderAndNullsAreElements() {
        assertThat(unrolled(Arrays.asList(1, null, 3), DISPLAY)).containsExactly(1, null, 3);
    }
}
