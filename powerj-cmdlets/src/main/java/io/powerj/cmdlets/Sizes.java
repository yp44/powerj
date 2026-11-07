package io.powerj.cmdlets;


import io.powerj.api.Bytes;
import io.powerj.text.RecordView;

/** Sizes in bytes: which values a {@link io.powerj.api.Row} shows in readable form ({@code 14.2 KB}). */
final class Sizes {

    private Sizes() {
    }

    /** {@code true} if {@code property} is a {@link Bytes} component of the record {@code value}. */
    static boolean isSizeProperty(Object value, String property) {
        if (value == null || property == null || !value.getClass().isRecord()) {
            return false;
        }
        RecordView.Component component = RecordView.of(value.getClass()).component(property);
        return component != null && component.bytes();
    }
}
