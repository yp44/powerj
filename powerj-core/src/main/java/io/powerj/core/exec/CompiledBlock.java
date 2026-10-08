package io.powerj.core.exec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import io.powerj.api.ScriptBlock;
import io.powerj.core.lang.Ast.Expression;

/**
 * Parsed block or lambda, ready to be evaluated for each pipeline object or called by Java (FR-33b).
 *
 * @param parameters declared parameters ({@code f -> …}, {@code (a, b) -> …}), or {@code null} for a
 *                   block without a declared parameter, whose single argument is {@code $_}
 * @param captured   parameters of the enclosing lambdas, captured at creation as in Java
 *                   ({@code f -> … anyMatch(e -> f.name.endsWith(e))})
 */
record CompiledBlock(String source, List<String> parameters, Expression body, Evaluator evaluator,
                     Map<String, Object> captured) implements ScriptBlock {

    /**
     * @throws PjException          if the evaluation fails (message intended for the user)
     * @throws CancellationException if the user requested cancellation
     */
    @Override
    public Object invoke(Object current) {
        return apply(new Object[] {current});
    }

    /** Calls the block with the arguments received from Java (or the pipeline object). */
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
