package io.powerj.core.assist;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.powerj.api.Row;
import io.powerj.core.error.PjException;
import io.powerj.core.java.JavaClasses;
import io.powerj.core.java.JavaInvoker;
import io.powerj.core.java.Members;
import io.powerj.core.java.PropertyAccess;
import io.powerj.core.lang.Ast;
import io.powerj.core.lang.ExpressionParser;
import io.powerj.core.runtime.CommandCatalog;
import io.powerj.core.runtime.Resolved;

/**
 * What an expression denotes, found without running it (FR-24): the type of a variable, of a property by its
 * signature, of the output of the cmdlet upstream. The one thing read from a real value is what
 * {@link PropertyAccess#peek} reads.
 */
final class TypeInference {

    private static final Pattern LAMBDA_PARAM = Pattern.compile("\\s*([\\p{L}_][\\p{L}\\p{N}_]*)\\s*->");

    private final CommandCatalog catalog;

    TypeInference(CommandCatalog catalog) {
        this.catalog = catalog;
    }

    /** Types known at the cursor: the objects that the previous stage sends and the parameters of the lambdas. */
    Scope scope(String line, Frame frame) {
        Map<String, Class<?>> parameters = new LinkedHashMap<>();
        Class<?> current = null;
        Row sample = null;
        for (Frame f = frame; f != null; f = f.parent) {
            if (f.kind != Frame.Kind.BLOCK) {
                continue;
            }
            Class<?> type = f.parent != null && f.parent.kind == Frame.Kind.COMMAND ? upstreamType(line, f.parent) : null;
            if (current == null) {
                current = type;
                if (type == Row.class) {
                    sample = upstreamColumns(line, f.parent);
                }
            }
            Matcher param = LAMBDA_PARAM.matcher(line).region(f.start, line.length());
            if (param.lookingAt()) {
                parameters.putIfAbsent(param.group(1), type == null ? Object.class : type);
            }
        }
        return new Scope(current, sample, parameters);
    }

    /**
     * Row with the columns that the cmdlet of the stage before the current one declares from its arguments
     * ({@code Cmdlet.columns}, e.g. {@code select}), looking through the stages that let objects through unchanged;
     * values {@code null}. {@code null} if no columns are known.
     */
    private Row upstreamColumns(String line, Frame command) {
        List<Integer> starts = command.stageStarts;
        for (int k = starts.size() - 2; k >= 0; k--) {
            String stage = line.substring(starts.get(k), starts.get(k + 1) - 1).strip();
            String name = LineText.splitWords(stage).getFirst();
            var cmdlet = catalog.cmdlet(name);
            if (cmdlet.isEmpty()) {
                return null;
            }
            if (cmdlet.get().passThrough()) {
                continue;
            }
            List<String> names;
            try {
                names = cmdlet.get().columns(stage.substring(name.length()));
            } catch (RuntimeException e) {
                return null; // a module's cmdlet must never break completion
            }
            if (names == null || names.isEmpty()) {
                return null;
            }
            Map<String, Object> columns = new LinkedHashMap<>();
            names.forEach(column -> columns.put(column, null));
            return Row.of(columns);
        }
        return null;
    }

    /** Type of the objects received by the current stage: output of the previous cmdlet (FR-24). */
    private Class<?> upstreamType(String line, Frame command) {
        List<Integer> starts = command.stageStarts;
        for (int k = starts.size() - 2; k >= 0; k--) {
            String stage = line.substring(starts.get(k), starts.get(k + 1) - 1).strip();
            String name = LineText.splitWords(stage).getFirst();
            if (name.startsWith("^")) {
                return String.class;
            }
            switch (catalog.resolve(name)) {
                case Resolved.CmdletCall(var cmdlet) -> {
                    Class<?> output = cmdlet.output();
                    if (output != Object.class) {
                        return output;
                    }
                    if (cmdlet.passThrough()) {
                        continue; // where, sort…: the objects go through unchanged
                    }
                    return null;
                }
                case Resolved.NativeCall _ -> {
                    return String.class;
                }
                case Resolved.BuiltinCall _, Resolved.Unknown _ -> {
                    return null;
                }
            }
        }
        return null;
    }

    /** What the text of a receiver ({@code $x.a}, {@code Math}, {@code (ls)}) denotes. */
    TypeInfo typeOf(String receiver, Scope scope) {
        if (receiver.isBlank()) {
            return new TypeInfo.Unknown();
        }
        Ast.Expression expression;
        try {
            expression = ExpressionParser.parse(receiver, catalog.session().java()::isStaticReference);
        } catch (RuntimeException e) {
            return new TypeInfo.Unknown();
        }
        return typeOf(expression, scope);
    }

    private TypeInfo typeOf(Ast.Expression expression, Scope scope) {
        return switch (expression) {
            case Ast.VariableExpression(var name, var accessors) when accessors.isEmpty() -> variableType(name, scope);
            case Ast.Name(var name) -> {
                if (scope.parameters().containsKey(name)) {
                    Class<?> type = scope.parameters().get(name);
                    yield type == Row.class && scope.sample() != null
                            ? new TypeInfo.Instance(type, scope.sample()) : TypeInfo.instance(type);
                }
                yield catalog.session().java().simpleClass(name).<TypeInfo>map(TypeInfo.Static::new)
                        .orElse(new TypeInfo.Package(name));
            }
            case Ast.Get(var target, var name) -> member(typeOf(target, scope), name);
            case Ast.Invoke(var target, var method, var arguments) -> invocation(typeOf(target, scope), method, arguments.size());
            case Ast.At(var target, var index) -> elementOf(typeOf(target, scope), index);
            case Ast.SpreadGet(var target, var name) -> {
                TypeInfo element = member(elementOf(typeOf(target, scope)), name);
                yield element instanceof TypeInfo.Instance(var type, _) ? new TypeInfo.ListOf(type) : new TypeInfo.Unknown();
            }
            case Ast.New(var type, _) -> catalog.session().java().find(type).<TypeInfo>map(TypeInfo::instance)
                    .orElse(new TypeInfo.Unknown());
            case Ast.Cast(var type, _) -> catalog.session().java().find(type).<TypeInfo>map(TypeInfo::instance)
                    .orElse(new TypeInfo.Unknown());
            case Ast.StringExpression _ -> TypeInfo.instance(String.class);
            case Ast.Literal(var value) -> value == null ? new TypeInfo.Unknown() : new TypeInfo.Instance(value.getClass(), value);
            case Ast.SubExpression(var pipeline) -> pipelineType(pipeline);
            case Ast.ListLiteral _ -> TypeInfo.instance(List.class);
            case Ast.Now() -> TypeInfo.instance(Instant.class);
            default -> new TypeInfo.Unknown();
        };
    }

    private TypeInfo variableType(String name, Scope scope) {
        if (name.equals("_")) {
            if (scope.sample() != null) {
                return new TypeInfo.Instance(Row.class, scope.sample());
            }
            return scope.current() == null ? new TypeInfo.Unknown() : TypeInfo.instance(scope.current());
        }
        try {
            Object value = catalog.session().variable(name);
            return value == null ? new TypeInfo.Unknown() : new TypeInfo.Instance(value.getClass(), value);
        } catch (PjException e) {
            return new TypeInfo.Unknown();
        }
    }

    /** {@code (ls)}: list of the outputs of the last cmdlet of the pipeline. */
    private TypeInfo pipelineType(Ast.Pipeline pipeline) {
        if (pipeline.stages().getLast().body() instanceof Ast.Command(var name, var forceNative, _)) {
            if (forceNative) {
                return new TypeInfo.ListOf(String.class);
            }
            var cmdlet = catalog.cmdlet(name);
            if (cmdlet.isPresent() && cmdlet.get().output() != Object.class) {
                return new TypeInfo.ListOf(cmdlet.get().output());
            }
        }
        return new TypeInfo.Unknown();
    }

    private TypeInfo member(TypeInfo target, String name) {
        return switch (target) {
            case TypeInfo.Static(var type) -> {
                Optional<Field> field = JavaClasses.staticField(type, name);
                if (field.isPresent()) {
                    yield TypeInfo.instance(field.get().getType());
                }
                yield JavaClasses.nested(type, name).<TypeInfo>map(TypeInfo.Static::new).orElse(new TypeInfo.Unknown());
            }
            case TypeInfo.Package(var prefix) -> {
                String qualified = prefix + "." + name;
                yield catalog.session().java().find(qualified).<TypeInfo>map(TypeInfo.Static::new)
                        .orElse(new TypeInfo.Package(qualified));
            }
            case TypeInfo.Instance(var type, var value) -> {
                // The real value only where reading it does nothing (C-23); otherwise the signature decides.
                Object property = value == null ? null : PropertyAccess.peek(value, name);
                yield property == null ? propertyType(type, name) : new TypeInfo.Instance(property.getClass(), property);
            }
            case TypeInfo.ListOf _, TypeInfo.Unknown _ -> new TypeInfo.Unknown();
        };
    }

    private static TypeInfo propertyType(Class<?> type, String name) {
        if (type.isRecord()) {
            for (RecordComponent c : type.getRecordComponents()) {
                if (c.getName().equalsIgnoreCase(name)) {
                    return TypeInfo.instance(c.getType());
                }
            }
        }
        return Members.getter(type, name).<TypeInfo>map(m -> TypeInfo.instance(m.getReturnType()))
                .or(() -> Members.field(type, name).map(f -> TypeInfo.instance(f.getType())))
                .orElse(new TypeInfo.Unknown());
    }

    private static TypeInfo invocation(TypeInfo target, String method, int arity) {
        List<Method> candidates = switch (target) {
            case TypeInfo.Static(var type) -> Arrays.stream(type.getMethods())
                    .filter(m -> Modifier.isStatic(m.getModifiers()) && m.getName().equals(method)).toList();
            case TypeInfo.Instance(var type, _) -> JavaInvoker.instanceMethods(type).getOrDefault(method, List.of());
            case TypeInfo.ListOf _ -> JavaInvoker.instanceMethods(List.class).getOrDefault(method, List.of());
            default -> List.of();
        };
        return candidates.stream()
                .filter(m -> m.getParameterCount() == arity || m.isVarArgs())
                .findFirst()
                .or(() -> candidates.stream().findFirst())
                .<TypeInfo>map(m -> m.getReturnType() == void.class ? new TypeInfo.Unknown() : TypeInfo.instance(m.getReturnType()))
                .orElse(new TypeInfo.Unknown());
    }

    /** The element that {@code *.} applies to: of a list, of an array, or the value itself. */
    static TypeInfo elementOf(TypeInfo target) {
        return elementOf(target, null);
    }

    /** Element of a list, of an array ({@code $f[0]}) or target of a {@code *.}. */
    private static TypeInfo elementOf(TypeInfo target, Ast.Expression index) {
        return switch (target) {
            case TypeInfo.ListOf(var element) -> TypeInfo.instance(element);
            case TypeInfo.Instance(var type, var value) when value instanceof List<?> list && !list.isEmpty() -> {
                Object element = list.getFirst();
                if (index instanceof Ast.Literal(var position) && position instanceof Integer i) {
                    int at = i < 0 ? list.size() + i : i;
                    if (at >= 0 && at < list.size()) {
                        element = list.get(at);
                    }
                }
                yield element == null ? new TypeInfo.Unknown() : new TypeInfo.Instance(element.getClass(), element);
            }
            case TypeInfo.Instance(var type, _) when type.isArray() -> TypeInfo.instance(type.componentType());
            case TypeInfo.Instance(var type, var value) when !(value instanceof Collection<?>) && index == null ->
                    target; // *. on a single value: the value itself
            default -> new TypeInfo.Unknown();
        };
    }
}
