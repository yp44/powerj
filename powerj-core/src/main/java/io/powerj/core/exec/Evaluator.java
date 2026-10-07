package io.powerj.core.exec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Ast.Expression;
import io.powerj.core.lang.Ast.Operator;
import io.powerj.core.lang.StringPart;

/**
 * Évalue les expressions : arguments, valeurs en tête de ligne, et contenu des blocs {@code { … }}.
 * L'objet courant {@code $_} est lié par {@link #CURRENT} pendant l'évaluation d'un bloc.
 */
final class Evaluator {

    /** Objet courant {@code $_} d'un bloc. */
    static final ScopedValue<Object> CURRENT = ScopedValue.newInstance();

    /** Exécute le pipeline d'une sous-expression {@code ( … )} et renvoie ses valeurs. */
    @FunctionalInterface
    interface PipelineRunner {
        List<Object> capture(Ast.Pipeline pipeline) throws InterruptedException;
    }

    private final Session session;
    private final PipelineRunner runner;

    Evaluator(Session session, PipelineRunner runner) {
        this.session = session;
        this.runner = runner;
    }

    Object evaluate(Expression expression) throws InterruptedException {
        return switch (expression) {
            case Ast.Literal(var value) -> value;
            case Ast.VariableExpression(var name, var accessors) -> PropertyAccess.apply(variable(name), accessors);
            case Ast.StringExpression(var parts) -> interpolate(parts);
            case Ast.SubExpression(var pipeline, var accessors) ->
                    PropertyAccess.apply(single(runner.capture(pipeline)), accessors);
            case Ast.BlockExpression(var source, var body) -> new CompiledBlock(source, body, this);
            case Ast.Get(var target, var name) -> PropertyAccess.property(evaluate(target), name);
            case Ast.At(var target, var index) -> at(evaluate(target), evaluate(index));
            case Ast.Invoke(var target, var method, var arguments) -> {
                Object receiver = evaluate(target);
                List<Object> values = new ArrayList<>(arguments.size());
                for (Expression argument : arguments) {
                    values.add(evaluate(argument));
                }
                yield MethodInvoker.invoke(receiver, method, values);
            }
            case Ast.Binary(var op, var left, var right) -> switch (op) {
                case AND -> Operators.bool(op, evaluate(left)) && Operators.bool(op, evaluate(right));
                case OR -> Operators.bool(op, evaluate(left)) || Operators.bool(op, evaluate(right));
                default -> Operators.binary(op, evaluate(left), evaluate(right));
            };
            case Ast.Unary(var op, var operand) -> Operators.unary(op, evaluate(operand));
            case Ast.Conditional(var condition, var whenTrue, var whenFalse) ->
                    Operators.bool(Operator.AND, evaluate(condition)) ? evaluate(whenTrue) : evaluate(whenFalse);
            case Ast.ListLiteral(var elements) -> {
                List<Object> values = new ArrayList<>(elements.size());
                for (Expression element : elements) {
                    values.add(evaluate(element));
                }
                yield java.util.Collections.unmodifiableList(values);
            }
            case Ast.Now() -> Instant.now();
        };
    }

    private Object variable(String name) {
        if (name.equals("_")) {
            if (!CURRENT.isBound()) {
                throw new PjException("$_ n'existe que dans un bloc { } appliqué aux objets d'un pipeline");
            }
            return CURRENT.get();
        }
        return session.variable(name);
    }

    private String interpolate(List<StringPart> parts) {
        var text = new StringBuilder();
        for (StringPart part : parts) {
            switch (part) {
                case StringPart.Text(var t) -> text.append(t);
                case StringPart.Interpolation(var name, var accessors) ->
                        text.append(Values.text(PropertyAccess.apply(variable(name), accessors)));
            }
        }
        return text.toString();
    }

    /** {@code x[i]} : index entier sur liste, tableau ou chaîne ; clé sur une {@code Map}. */
    private static Object at(Object target, Object index) {
        if (target instanceof Map<?, ?> map) {
            return map.get(index);
        }
        if (index instanceof Integer || index instanceof Long || index instanceof Short || index instanceof Byte) {
            return PropertyAccess.index(target, ((Number) index).intValue());
        }
        throw new PjException("index entier attendu, reçu " + Operators.describe(index));
    }

    /** Valeur d'une capture (FR-31) : aucune → null, une → elle-même, plusieurs → liste. */
    static Object single(List<Object> values) {
        return switch (values.size()) {
            case 0 -> null;
            case 1 -> values.getFirst();
            default -> java.util.Collections.unmodifiableList(new ArrayList<>(values));
        };
    }
}
