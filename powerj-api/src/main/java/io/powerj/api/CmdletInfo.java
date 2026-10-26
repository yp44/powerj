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

    /**
     * {@code true} if the cmdlet may run in preview mode (FR-66): it changes nothing (no file, no environment, no
     * network side effect) and only produces objects. Default {@code false}: a cmdlet that does not declare it is
     * never run while the user types. A cmdlet whose options can change something refines the decision with
     * {@link Previewable}.
     */
    boolean preview() default false;

    /**
     * {@code true} if the cmdlet takes the short form {@code property operator value} as its arguments
     * ({@code where size > 1mb}): the {@code >} that follows the first word is then a comparison operator, not a
     * redirection. Default {@code false}: {@code >} redirects the output, as after any command.
     */
    boolean comparison() default false;

    /**
     * {@code true} if the cmdlet emits the objects it receives, unchanged (filter, sort, choice of positions): Tab
     * completion then looks through it to find the type of {@code $_} in the next stage
     * ({@code ls | sort name | map { $_.name }}).
     */
    boolean passThrough() default false;
}
