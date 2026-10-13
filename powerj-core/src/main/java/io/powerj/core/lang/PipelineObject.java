package io.powerj.core.lang;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Optional;

/**
 * Detects the use of the pipeline object {@code $_} in an expression (FR-36e): {@code ls | $_.name} is then a
 * stage evaluated for each object. The {@code $_} of a nested block or lambda is its own and does not count.
 */
final class PipelineObject {

    private static final String NAME = "_";

    private PipelineObject() {
    }

    static boolean isUsedBy(Ast.Expression expression) {
        return uses(expression);
    }

    private static boolean uses(Object node) {
        return switch (node) {
            case null -> false;
            case Ast.VariableExpression(var name, var accessors) -> name.equals(NAME) || uses(accessors);
            case StringPart.Interpolation(var name, var accessors) -> name.equals(NAME) || uses(accessors);
            case Ast.BlockExpression _, Ast.Lambda _ -> false;
            case Collection<?> elements -> elements.stream().anyMatch(PipelineObject::uses);
            case Optional<?> value -> value.isPresent() && uses(value.get());
            case Record r -> components(r);
            default -> false;
        };
    }

    private static boolean components(Record node) {
        for (RecordComponent component : node.getClass().getRecordComponents()) {
            try {
                if (uses(component.getAccessor().invoke(node))) {
                    return true;
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }
        return false;
    }
}
