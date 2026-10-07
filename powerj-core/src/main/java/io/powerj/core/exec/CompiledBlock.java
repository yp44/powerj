package io.powerj.core.exec;

import java.util.Map;
import java.util.concurrent.CancellationException;

import io.powerj.api.ScriptBlock;
import io.powerj.core.lang.Ast.Expression;

/** Bloc {@code { … }} analysé, prêt à être évalué pour chaque objet du pipeline ou appelé par Java. */
record CompiledBlock(String source, Expression body, Evaluator evaluator) implements ScriptBlock {

    /**
     * @throws PjException          si l'évaluation échoue (message destiné à l'utilisateur)
     * @throws CancellationException si l'utilisateur a demandé l'annulation
     */
    @Override
    public Object invoke(Object current) {
        return invokeWith(current, Map.of());
    }

    /** Évalue avec {@code $_ = current} et des paramètres nommés ({@code $a}, {@code $b}, {@code $args}). */
    Object invokeWith(Object current, Map<String, Object> locals) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
        try {
            return ScopedValue.where(Evaluator.CURRENT, current).where(Evaluator.LOCALS, locals)
                    .call(() -> evaluator.evaluate(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    @Override
    public String toString() {
        return "{ " + source + " }";
    }
}
