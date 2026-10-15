package io.powerj.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Text component whose parts are highlighted in color on a terminal (specification FR-64). The parts are
 * given by another component of the same record, of type {@code List<Span>}:
 * <pre>
 * public record GrepMatch(Path path, int line, &#64;Highlight("matches") String text, List&lt;Span&gt; matches) { }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Highlight {

    /** Name of the {@code List<Span>} component giving the parts to highlight. */
    String value();
}
