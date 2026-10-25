package io.powerj.core.exec;

import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.stream.BaseStream;

import io.powerj.api.Collected;

/**
 * Turns a value into the objects it stands for: the elements of a collection, an array, an iterator, a stream.
 * One place for the rule that used to be written four times, with drifts (review C-07), among them a stream that was
 * left open when {@code *.} unrolled it.
 * <p>
 * A stream is always closed once it was read, even when {@code each} throws. A {@link Path} (which is an
 * {@code Iterable<Path>}), a {@code String}, a {@code Map}, a record are never unrolled.
 */
final class ValueUnroller {

    /** What a caller wants unrolled beyond collections, arrays, iterators and streams. */
    enum Mode {
        /** Between two stages (FR-30b): optionals are unrolled, and a {@link Collected} list is passed whole (FR-36d). */
        BETWEEN_STAGES(true, true),
        /** What is shown to the user: optionals are shown as they are; a {@link Collected} list is shown element by element. */
        DISPLAY(false, false),
        /** The targets of {@code *.} and {@code *::}: optionals are unrolled, a {@link Collected} list too. */
        TARGETS(true, false);

        private final boolean optionals;
        private final boolean keepCollected;

        Mode(boolean optionals, boolean keepCollected) {
            this.optionals = optionals;
            this.keepCollected = keepCollected;
        }
    }

    private ValueUnroller() {
    }

    /**
     * Passes each element of {@code value} to {@code each}.
     *
     * @return {@code true} if {@code value} is something that unrolls (even to nothing: an empty list); {@code false}
     *         if it is a single value, in which case {@code each} was not called and the caller deals with it
     */
    static boolean unroll(Object value, Mode mode, Consumer<Object> each) {
        switch (value) {
            case null -> {
                return false;
            }
            case Path _ -> {
                return false; // a Path is also an Iterable<Path>
            }
            case Collected<?> list when mode.keepCollected -> {
                return false;
            }
            case Iterable<?> items -> items.forEach(each);
            case BaseStream<?, ?> stream -> { // Stream, and IntStream, LongStream, DoubleStream (boxed elements)
                try (stream) {
                    stream.iterator().forEachRemaining(each);
                }
            }
            case Iterator<?> iterator -> iterator.forEachRemaining(each);
            case Optional<?> optional when mode.optionals -> optional.ifPresent(each);
            case OptionalInt optional when mode.optionals -> optional.ifPresent(each::accept);
            case OptionalLong optional when mode.optionals -> optional.ifPresent(each::accept);
            case OptionalDouble optional when mode.optionals -> optional.ifPresent(each::accept);
            case Object array when array.getClass().isArray() -> {
                for (int i = 0, length = Array.getLength(array); i < length; i++) {
                    each.accept(Array.get(array, i));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
