package io.powerj.cmdlets;

import java.lang.reflect.RecordComponent;

import io.powerj.api.Bytes;

/** Sizes in bytes: which values a {@link io.powerj.api.Row} shows in readable form ({@code 14.2 KB}). */
final class Sizes {

    private Sizes() {
    }

    /** {@code true} if {@code property} is a {@link Bytes} component of the record {@code value}. */
    static boolean isSizeProperty(Object value, String property) {
        if (value == null || property == null || !value.getClass().isRecord()) {
            return false;
        }
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            if (component.getName().equals(property) && component.isAnnotationPresent(Bytes.class)) {
                return true;
            }
        }
        return false;
    }
}
