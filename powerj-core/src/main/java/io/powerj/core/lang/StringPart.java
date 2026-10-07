package io.powerj.core.lang;

import java.util.List;

/** Morceau d'une chaîne entre guillemets : texte littéral ou variable interpolée. */
public sealed interface StringPart {

    record Text(String text) implements StringPart { }

    record Interpolation(String variable, List<Accessor> accessors) implements StringPart { }
}
