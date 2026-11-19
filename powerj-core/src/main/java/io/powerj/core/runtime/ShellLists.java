package io.powerj.core.runtime;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * The lists that the shell makes itself: what an assignment captures ({@code $f = ls}), a list literal ({@code [1, 2]}), the
 * results of {@code *.}. They hold their elements and cannot be changed, as {@code Collections.unmodifiableList} of a copy did;
 * having a type of their own lets completion tell them from a JDK wrapper around a list that may be lazy or a third party's,
 * which it must not read while the user types (review of #219, C-23).
 */
public final class ShellLists {

    private ShellLists() {
    }

    /** A list of the shell holding a copy of {@code values}, {@code null} elements included. */
    static List<Object> of(List<?> values) {
        return new Values(values.toArray());
    }

    /** {@code true} if {@code value} is a list that the shell made: reading it runs no other code. */
    public static boolean isOne(Object value) {
        return value instanceof Values;
    }

    /**
     * Not public: the methods that a line calls on such a list are those of {@link List}, as for the lists of the JDK (the
     * preview, which allows the reading methods of {@code List}, sees no other class).
     */
    private static final class Values extends AbstractList<Object> implements RandomAccess {
        private final Object[] elements;

        Values(Object[] elements) {
            this.elements = elements;
        }

        @Override
        public Object get(int index) {
            return elements[index];
        }

        @Override
        public int size() {
            return elements.length;
        }
    }
}
