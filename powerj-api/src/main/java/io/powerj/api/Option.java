package io.powerj.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Option d'un cmdlet, posée sur un composant du record de paramètres. Style Unix : {@code -r},
 * {@code --recurse}, {@code --filter *.java}, {@code --filter=*.java}. Un composant {@code boolean} est
 * un interrupteur sans valeur ; un composant {@code List} positionnel reçoit tous les arguments restants.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Option {

    /** Lettre de la forme courte ({@code -r}) ; aucune par défaut. */
    char shortName() default '\0';

    /** Nom de la forme longue ({@code --recurse}) ; par défaut, le nom du composant. */
    String longName() default "";

    /** Option obligatoire. */
    boolean mandatory() default false;

    /** Position ({@code >= 0}) si l'argument peut être donné sans nom d'option. */
    int position() default -1;

    /** Description affichée par l'aide. */
    String description() default "";
}
