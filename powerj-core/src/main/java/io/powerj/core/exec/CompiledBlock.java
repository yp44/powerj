package io.powerj.core.exec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import io.powerj.api.ScriptBlock;
import io.powerj.core.lang.Ast.Expression;

/**
 * Bloc ou lambda analysé, prêt à être évalué pour chaque objet du pipeline ou appelé par Java (FR-33b).
 *
 * @param parameters paramètres déclarés ({@code f -> …}, {@code (a, b) -> …}), ou {@code null} pour un
 *                   bloc sans paramètre déclaré, dont l'unique argument est {@code $_}
 * @param captured   paramètres des lambdas englobantes, capturés à la création comme en Java
 *                   ({@code f -> … anyMatch(e -> f.name.endsWith(e))})
 */
record CompiledBlock(String source, List<String> parameters, Expression body, Evaluator evaluator,
                     Map<String, Object> captured) implements ScriptBlock {

    /**
     * @throws PjException          si l'évaluation échoue (message destiné à l'utilisateur)
     * @throws CancellationException si l'utilisateur a demandé l'annulation
     */
    @Override
    public Object invoke(Object current) {
        return apply(new Object[] {current});
    }

    /** Appelle le bloc avec les arguments reçus de Java (ou l'objet du pipeline). */
    Object apply(Object[] args) {
        if (parameters == null) {
            if (args.length > 1) {
                throw new PjException("ce bloc reçoit " + args.length + " arguments : déclarer ses paramètres comme en"
                        + " Java, ex. { (a, b) -> a.compareTo(b) }");
            }
            return evaluate(args.length == 0 ? null : args[0], captured);
        }
        if (parameters.size() != args.length) {
            throw new PjException("la lambda { " + source + " } déclare " + parameters.size() + " paramètre(s), "
                    + args.length + " reçu(s)");
        }
        Map<String, Object> locals = new HashMap<>(captured);
        for (int i = 0; i < args.length; i++) {
            locals.put(parameters.get(i), args[i] == null ? Evaluator.NULL : args[i]);
        }
        return evaluate(args.length == 1 ? args[0] : null, Map.copyOf(locals));
    }

    private Object evaluate(Object current, Map<String, Object> locals) {
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
