package io.powerj.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Décrit un cmdlet : son nom (seul et unique, court, en minuscules) et sa documentation. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CmdletInfo {

    /** Nom de la commande, ex. {@code ls}. */
    String name();

    /** Catégorie dans {@code help}. */
    String category() default "Divers";

    /** Résumé d'une ligne. */
    String summary();

    /** Exemples affichés par {@code help <nom>}. */
    String[] examples() default {};
}
