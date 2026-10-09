package io.powerj.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes a cmdlet: its name (single and unique, short, lowercase) and its documentation. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CmdletInfo {

    /** Command name, e.g. {@code ls}. */
    String name();

    /** Category in {@code help}. */
    String category() default "Misc";

    /** One-line summary. */
    String summary();

    /** Examples displayed by {@code help <name>}. */
    String[] examples() default {};
}
