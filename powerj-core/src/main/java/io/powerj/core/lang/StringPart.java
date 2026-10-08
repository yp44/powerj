package io.powerj.core.lang;

import java.util.List;

/** Piece of a double-quoted string: literal text, interpolated variable or {@code $( … )}. */
public sealed interface StringPart {

    record Text(String text) implements StringPart { }

    record Interpolation(String variable, List<Accessor> accessors) implements StringPart { }

    /** {@code $(expression ou commande)}: value inserted into the string. */
    record Embedded(Ast.Expression expression) implements StringPart { }
}
