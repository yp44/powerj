package io.powerj.core.exec;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

import io.powerj.api.ScriptBlock;

/**
 * Block {@code { … }} passed to a parameter of a functional interface type ({@code Predicate},
 * {@code Function}, {@code Comparator}…): converted into an implementation of the interface (specification FR-51).
 * The lambda's parameters receive the arguments; a block without a declared parameter receives its single
 * argument in {@code $_}; a method reference is called with the arguments (FR-33b).
 */
final class FunctionalAdapter {

    private static final ClassValue<Method> SINGLE_ABSTRACT_METHOD = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            return findSingleAbstractMethod(type);
        }
    };

    private FunctionalAdapter() {
    }

    static boolean isFunctional(Class<?> type) {
        return type.isInterface() && SINGLE_ABSTRACT_METHOD.get(type) != null;
    }

    static Object adapt(ScriptBlock block, Class<?> type) {
        Method sam = SINGLE_ABSTRACT_METHOD.get(type);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals(sam.getName()) && method.getParameterCount() == sam.getParameterCount()
                    && !method.isDefault()) {
                return result(sam, call(block, args == null ? new Object[0] : args));
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return switch (method.getName()) {
                case "toString" -> block.toString();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        };
        return Proxy.newProxyInstance(FunctionalAdapter.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object call(ScriptBlock block, Object[] args) {
        return switch (block) {
            case CompiledBlock compiled -> compiled.apply(args);
            case MethodReference reference -> reference.apply(args);
            default -> block.invoke(args.length == 0 ? null : args[0]);
        };
    }

    /** Value of the block converted to the return type of the method (FR-50). */
    private static Object result(Method sam, Object value) {
        Class<?> type = sam.getReturnType();
        if (type == void.class) {
            return null;
        }
        if (type == boolean.class && !(value instanceof Boolean)) {
            throw new PjException(Messages.get("block.booleanExpected", sam.getDeclaringClass().getSimpleName(),
                    sam.getName(), Operators.describe(value)));
        }
        if (JavaInvoker.cost(type, value) == JavaInvoker.NO_MATCH) {
            throw new PjException(Messages.get("block.wrongReturn", Operators.describe(value), type.getSimpleName(),
                    sam.getDeclaringClass().getSimpleName(), sam.getName()));
        }
        return JavaInvoker.convert(type, value);
    }

    private static Method findSingleAbstractMethod(Class<?> type) {
        if (!type.isInterface()) {
            return null;
        }
        Method found = null;
        for (Method m : type.getMethods()) {
            if (!Modifier.isAbstract(m.getModifiers()) || isObjectMethod(m)) {
                continue;
            }
            if (found != null && !(found.getName().equals(m.getName())
                    && found.getParameterCount() == m.getParameterCount())) {
                return null;
            }
            found = found == null ? m : found;
        }
        return found;
    }

    /** Public methods of {@code Object} redeclared by an interface ({@code Comparator.equals}). */
    private static boolean isObjectMethod(Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException _) {
            return false;
        }
    }
}
