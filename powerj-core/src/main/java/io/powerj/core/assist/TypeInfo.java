package io.powerj.core.assist;

/** What an expression denotes, inferred without executing it. */
sealed interface TypeInfo {

    /** A value of this type; {@code value} is the real value when it is known, {@code null} when only the type is. */
    record Instance(Class<?> type, Object value) implements TypeInfo { }

    /** List whose element type is known ({@code (ls)}). */
    record ListOf(Class<?> element) implements TypeInfo { }

    record Static(Class<?> type) implements TypeInfo { }

    record Package(String name) implements TypeInfo { }

    record Unknown() implements TypeInfo { }

    static TypeInfo instance(Class<?> type) {
        return new Instance(type, null);
    }
}
