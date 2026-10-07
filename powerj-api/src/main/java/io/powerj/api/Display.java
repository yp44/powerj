package io.powerj.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Colonnes affichées par défaut pour un record de sortie (spécification FR-30). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Display {

    /** Noms des composants affichés, dans l'ordre. */
    String[] columns();
}
