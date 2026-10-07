package io.powerj.core.lang;

/** Accès appliqué à une valeur : propriété {@code .nom} ou index {@code [n]}. */
public sealed interface Accessor {

    record Property(String name) implements Accessor { }

    /** Index ; négatif = depuis la fin ({@code [-1]} est le dernier élément). */
    record Index(int index) implements Accessor { }
}
