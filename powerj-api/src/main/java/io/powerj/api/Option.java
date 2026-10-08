package io.powerj.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cmdlet option, placed on a component of the parameter record. Unix style: {@code -r},
 * {@code --recurse}, {@code --filter *.java}, {@code --filter=*.java}. A {@code boolean} component is
 * a switch without a value; a positional {@code List} component receives all remaining arguments.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Option {

    /** Letter of the short form ({@code -r}); none by default. */
    char shortName() default '\0';

    /** Name of the long form ({@code --recurse}); by default, the component name. */
    String longName() default "";

    /** Mandatory option. */
    boolean mandatory() default false;

    /** Position ({@code >= 0}) if the argument can be given without an option name. */
    int position() default -1;

    /** Description displayed by the help. */
    String description() default "";
}
