package io.powerj.fx.model;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.temporal.Temporal;
import java.util.Date;

import io.powerj.api.Bytes;

/** What a column holds: it decides the alignment, the text of the cells and (later) how they are sorted and filtered. */
public enum ColumnKind {

    /** Nothing known yet (only {@code null} values so far): shown as text, and any other kind replaces it. */
    UNKNOWN,
    TEXT,
    NUMBER,
    /** A number of bytes, shown in readable form ({@code 14.2 KB}). */
    SIZE,
    DATE;

    /** Kind of a column declared with {@code type}. */
    public static ColumnKind ofType(Class<?> type) {
        if (type.isPrimitive()) {
            return type == boolean.class || type == char.class ? TEXT : NUMBER;
        }
        if (Number.class.isAssignableFrom(type)) {
            return NUMBER;
        }
        if (Temporal.class.isAssignableFrom(type) || Date.class.isAssignableFrom(type)) {
            return DATE;
        }
        return TEXT;
    }

    /** Kind of a record component: {@link Bytes} makes a size. */
    public static ColumnKind of(RecordComponent component) {
        return component.isAnnotationPresent(Bytes.class) && ofType(component.getType()) == NUMBER
                ? SIZE : ofType(component.getType());
    }

    /** Kind of the value of a column, {@link #UNKNOWN} for {@code null}. */
    public static ColumnKind ofValue(Object value) {
        return value == null ? UNKNOWN : ofType(value.getClass());
    }

    /** Kind of the return type of a getter. */
    static ColumnKind of(Method getter) {
        return ofType(getter.getReturnType());
    }

    /** Kind of a column that holds both: the same kind, otherwise plain text. */
    public ColumnKind merge(ColumnKind other) {
        if (this == other || other == UNKNOWN) {
            return this;
        }
        return this == UNKNOWN ? other : TEXT;
    }

    /** {@code true} if the cells are aligned to the right. */
    public boolean alignRight() {
        return this == NUMBER || this == SIZE;
    }
}
