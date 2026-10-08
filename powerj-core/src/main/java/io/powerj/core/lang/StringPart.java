package io.powerj.core.lang;

import java.util.List;

/** Morceau d'une chaîne entre guillemets : texte littéral, variable interpolée ou {@code $( … )}. */
public sealed interface StringPart {

    record Text(String text) implements StringPart { }

    record Interpolation(String variable, List<Accessor> accessors) implements StringPart { }

    /** {@code $(expression ou commande)} : valeur insérée dans la chaîne. */
    record Embedded(Ast.Expression expression) implements StringPart { }
}
