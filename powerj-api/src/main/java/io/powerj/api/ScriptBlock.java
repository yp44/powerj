package io.powerj.api;

/**
 * Expression block typed by the user, evaluated for a received object (specification FR-33b, FR-36):
 * lambda {@code { f -> f.size > 1mb && !f.dir }}, {@code $_} block {@code { $_.dir }}, or method
 * reference {@code FileEntry::name}.
 */
public interface ScriptBlock {

    /** Evaluates the block with {@code $_} equal to {@code current}. */
    Object invoke(Object current);

    /** Source text of the block, without the braces. */
    String source();

    /**
     * Evaluates the block as a condition (FR-33b): the result must be a boolean, as for a Java
     * {@code Predicate}.
     *
     * @throws IllegalStateException if the block returns something other than a boolean
     */
    default boolean test(Object current) {
        Object value = invoke(current);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException("le bloc doit renvoyer un booléen, reçu "
                + (value == null ? "null" : value.getClass().getSimpleName() + " " + value));
    }
}
