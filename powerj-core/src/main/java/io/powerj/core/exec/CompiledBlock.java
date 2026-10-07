package io.powerj.core.exec;

import java.util.concurrent.CancellationException;

import io.powerj.api.ScriptBlock;
import io.powerj.core.lang.Ast.Expression;

/** Bloc {@code { … }} analysé, prêt à être évalué pour chaque objet du pipeline. */
record CompiledBlock(String source, Expression body, Evaluator evaluator) implements ScriptBlock {

    /**
     * @throws PjException          si l'évaluation échoue (message destiné à l'utilisateur)
     * @throws CancellationException si l'utilisateur a demandé l'annulation
     */
    @Override
    public Object invoke(Object current) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
        try {
            return ScopedValue.where(Evaluator.CURRENT, current).call(() -> evaluator.evaluate(body));
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
