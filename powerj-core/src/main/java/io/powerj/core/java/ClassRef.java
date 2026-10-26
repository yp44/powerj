package io.powerj.core.java;

/** Class referenced in an expression ({@code Math}, {@code java.util.List}). */
public record ClassRef(Class<?> type) {

    @Override
    public String toString() {
        return type.getName();
    }
}
