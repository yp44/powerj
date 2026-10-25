package io.powerj.core.exec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.BaseStream;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Ast.Expression;
import io.powerj.core.lang.Ast.Operator;
import io.powerj.core.lang.StringPart;

/**
 * Evaluates expressions: arguments, values at the start of a line, contents of {@code { … }} blocks, Java
 * calls (specification §3.13). The current object {@code $_} is bound by {@link #CURRENT} during the evaluation
 * of a block; the parameters of a lambda ({@code f -> …}) by {@link #LOCALS} (FR-33b).
 */
final class Evaluator {

    /** Current object {@code $_} of a block. */
    static final ScopedValue<Object> CURRENT = ScopedValue.newInstance();

    /** Parameters of the lambda being evaluated, by name. */
    static final ScopedValue<Map<String, Object>> LOCALS = ScopedValue.newInstance();

    /** {@code null} value in {@link #LOCALS}. */
    static final Object NULL = new Object();

    /** Class referenced in an expression ({@code Math}, {@code java.util.List}). */
    record ClassRef(Class<?> type) {
        @Override
        public String toString() {
            return type.getName();
        }
    }

    /** Start of a qualified name that is not (yet) a class: {@code java}, {@code java.util}. */
    record PackageRef(String name) { }

    /** Runs the pipeline of a subexpression {@code ( … )} and returns its values. */
    @FunctionalInterface
    interface PipelineRunner {
        List<Object> capture(Ast.Pipeline pipeline) throws InterruptedException;
    }

    private final Session session;
    private final PipelineRunner runner;
    private final CallPolicy policy;

    Evaluator(Session session, PipelineRunner runner) {
        this.session = session;
        this.runner = runner;
        this.policy = new CallPolicy(session);
    }

    /** Value of the expression; a name that denotes neither a value nor a class is an error. */
    Object evaluate(Expression expression) throws InterruptedException {
        Object value = eval(expression);
        if (value instanceof PackageRef(var name)) {
            throw unknownName(name);
        }
        return value;
    }

    private Object eval(Expression expression) throws InterruptedException {
        return switch (expression) {
            case Ast.Literal(var value) -> value;
            case Ast.VariableExpression(var name, var accessors) -> PropertyAccess.apply(variable(name), accessors);
            case Ast.StringExpression(var parts) -> interpolate(parts);
            case Ast.SubExpression(var pipeline) -> single(runner.capture(pipeline));
            case Ast.BlockExpression(var source, var body) -> new CompiledBlock(source, null, body, this, captured());
            case Ast.Lambda(var source, var parameters, var body) ->
                    new CompiledBlock(source, parameters, body, this, captured());
            case Ast.MethodRef(var target, var method) -> methodReference(eval(target), method);
            case Ast.Name(var name) -> name(name);
            case Ast.Get(var target, var name) -> get(eval(target), name);
            case Ast.At(var target, var index) -> at(evaluate(target), evaluate(index));
            case Ast.SpreadGet(var target, var name) -> {
                List<Object> results = new ArrayList<>();
                for (Object element : elements(evaluate(target))) {
                    results.add(PropertyAccess.property(element, name));
                }
                yield Collections.unmodifiableList(results);
            }
            case Ast.SpreadInvoke(var target, var method, var arguments) -> {
                List<Object> receivers = elements(evaluate(target));
                List<Object> args = values(arguments);
                List<Object> results = new ArrayList<>();
                for (Object element : receivers) {
                    results.add(invoke(element, method, args));
                }
                yield Collections.unmodifiableList(results);
            }
            case Ast.Invoke(var target, var method, var arguments) -> invoke(eval(target), method, values(arguments));
            case Ast.New(var type, var arguments) -> JavaInvoker.construct(session.java().require(type), values(arguments));
            case Ast.Cast(var type, var operand) -> cast(session.java().require(type), evaluate(operand));
            case Ast.Binary(var op, var left, var right) -> switch (op) {
                case AND -> Operators.bool(op, evaluate(left)) && Operators.bool(op, evaluate(right));
                case OR -> Operators.bool(op, evaluate(left)) || Operators.bool(op, evaluate(right));
                default -> Operators.binary(op, evaluate(left), evaluate(right));
            };
            case Ast.Unary(var op, var operand) -> Operators.unary(op, evaluate(operand));
            case Ast.Conditional(var condition, var whenTrue, var whenFalse) ->
                    Operators.bool(Operator.AND, evaluate(condition)) ? evaluate(whenTrue) : evaluate(whenFalse);
            case Ast.ListLiteral(var elements) -> Collections.unmodifiableList(values(elements));
            case Ast.Now() -> Instant.now();
        };
    }

    /** Parameters of the current lambdas, captured by a block or a lambda created here. */
    private static Map<String, Object> captured() {
        return LOCALS.isBound() ? LOCALS.get() : Map.of();
    }

    private List<Object> values(List<Expression> expressions) throws InterruptedException {
        List<Object> values = new ArrayList<>(expressions.size());
        for (Expression e : expressions) {
            values.add(evaluate(e));
        }
        return values;
    }

    // --- Java names ---

    private Object name(String name) {
        if (LOCALS.isBound() && LOCALS.get().containsKey(name)) {
            Object value = LOCALS.get().get(name);
            return value == NULL ? null : value; // lambda parameter: takes precedence over classes
        }
        JavaClasses java = session.java();
        java.checkAmbiguity(name);
        return java.simpleClass(name).<Object>map(ClassRef::new).orElseGet(() -> new PackageRef(name));
    }

    /** {@code x.name}: static field, nested class, continuation of a qualified name, or property (FR-28). */
    private Object get(Object target, String name) {
        return switch (target) {
            case ClassRef(var type) -> JavaClasses.staticField(type, name).map(field -> {
                try {
                    return field.get(null);
                } catch (IllegalAccessException e) {
                    throw new PjException(PjError.of(Messages.get("java.fieldInaccessible", type.getSimpleName(), name), e));
                }
            }).or(() -> JavaClasses.nested(type, name).map(ClassRef::new)).orElseThrow(() ->
                    new PjException(Messages.get("java.noStaticField", type.getSimpleName(), name)));
            case PackageRef(var prefix) -> {
                String qualified = prefix + "." + name;
                yield session.java().find(qualified).<Object>map(ClassRef::new).orElseGet(() -> new PackageRef(qualified));
            }
            case null, default -> PropertyAccess.property(target, name);
        };
    }

    private Object invoke(Object target, String method, List<Object> args) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
        return switch (target) {
            case ClassRef(var type) -> JavaInvoker.invokeStatic(policy, type, method, args);
            case PackageRef(var prefix) -> throw prefix.contains(".")
                    ? new PjException(Messages.get("java.classNotFound", prefix))
                    : unknownName(prefix);
            case null, default -> JavaInvoker.invokeVirtual(policy, target, method, args);
        };
    }

    /** {@code Class::method}, {@code $object::method}, {@code FileEntry::name} (FR-33b). */
    private Object methodReference(Object target, String method) {
        return switch (target) {
            case ClassRef(var type) -> new MethodReference.OfClass(type, method, policy);
            case PackageRef(var name) when !name.contains(".") -> new MethodReference.ByTypeName(name, method, policy);
            case PackageRef(var name) -> throw new PjException(Messages.get("java.classNotFound", name));
            case null -> throw new PjException(Messages.get("methodref.onNull", method));
            default -> new MethodReference.Bound(target, method, policy);
        };
    }

    private static PjException unknownName(String name) {
        return new PjException(Messages.get("name.unknown", name));
    }

    /** {@code [type] value}: explicit conversion (FR-50). */
    private static Object cast(Class<?> type, Object value) {
        if (value == null) {
            if (type.isPrimitive()) {
                throw new PjException(Messages.get("java.conversionFailed", "null", type.getName()));
            }
            return null;
        }
        if (type.isPrimitive() && type != boolean.class
                && (value instanceof Number || value instanceof Character)) {
            Number n = value instanceof Character c ? (int) c : (Number) value;
            return switch (type.getName()) {
                case "int" -> n.intValue();
                case "long" -> n.longValue();
                case "short" -> n.shortValue();
                case "byte" -> n.byteValue();
                case "float" -> n.floatValue();
                case "double" -> n.doubleValue();
                default -> (char) n.intValue();
            };
        }
        if (JavaInvoker.box(type).isInstance(value)) {
            return value;
        }
        if (JavaInvoker.cost(type, value) != JavaInvoker.NO_MATCH) {
            return JavaInvoker.convert(type, value);
        }
        throw new PjException(Messages.get("cast.failed", Operators.describe(value), type.getSimpleName()));
    }

    // --- Variables and strings ---

    private Object variable(String name) {
        if (name.equals("_")) {
            if (!CURRENT.isBound()) {
                throw new PjException(Messages.get("variable.currentOutsideBlock"));
            }
            return CURRENT.get();
        }
        if (LOCALS.isBound() && LOCALS.get().containsKey(name)) {
            Object value = LOCALS.get().get(name);
            return value == NULL ? null : value;
        }
        Object value = session.variable(name);
        if (value instanceof BaseStream<?, ?> || value instanceof Iterator<?>) {
            // A stream or an iterator can be read only once: the preview would use it up (FR-66).
            PreviewGuard.deny(Messages.get("preview.off.readOnce", name));
        }
        return value;
    }

    private String interpolate(List<StringPart> parts) throws InterruptedException {
        var text = new StringBuilder();
        for (StringPart part : parts) {
            switch (part) {
                case StringPart.Text(var t) -> text.append(t);
                case StringPart.Interpolation(var name, var accessors) ->
                        text.append(Values.text(PropertyAccess.apply(variable(name), accessors)));
                case StringPart.Embedded(var expression) -> text.append(Values.text(evaluate(expression)));
            }
        }
        return text.toString();
    }

    /** {@code x[i]}: integer index on a list, array or string; key on a {@code Map}. */
    private static Object at(Object target, Object index) {
        if (target instanceof Map<?, ?> map) {
            return map.get(index);
        }
        if (index instanceof Integer || index instanceof Long || index instanceof Short || index instanceof Byte) {
            return PropertyAccess.index(target, ((Number) index).intValue());
        }
        throw new PjException(Messages.get("index.integerExpected", Operators.describe(index)));
    }

    /**
     * Elements targeted by {@code *.}: those of a collection, an array, a stream; a single value counts
     * as one element, {@code null} as none.
     */
    static List<Object> elements(Object value) {
        List<Object> elements = new ArrayList<>();
        if (value != null && !ValueUnroller.unroll(value, ValueUnroller.Mode.TARGETS, elements::add)) {
            elements.add(value);
        }
        return elements;
    }

    /** Value of a capture (FR-31): none → null, one → itself, several → list. */
    static Object single(List<Object> values) {
        return switch (values.size()) {
            case 0 -> null;
            case 1 -> values.getFirst();
            default -> Collections.unmodifiableList(new ArrayList<>(values));
        };
    }
}
