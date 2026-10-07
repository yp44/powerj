package io.powerj.core.lang;

/** Façon dont une instruction s'enchaîne à la précédente (spécification FR-04c). */
public enum Connector {
    /** Début de ligne ou {@code ;} : toujours exécutée. */
    ALWAYS,
    /** {@code &&} : exécutée si l'instruction précédente a réussi. */
    IF_SUCCESS,
    /** {@code ||} : exécutée si l'instruction précédente a échoué. */
    IF_FAILURE
}
