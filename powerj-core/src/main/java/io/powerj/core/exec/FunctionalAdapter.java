package io.powerj.core.exec;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

import io.powerj.api.ScriptBlock;

/**
 * Bloc {@code { … }} passé à un paramètre de type interface fonctionnelle ({@code Predicate},
 * {@code Function}, {@code Comparator}…) : converti en implémentation de l'interface (spécification FR-51).
 * Les paramètres de la lambda reçoivent les arguments ; un bloc sans paramètre déclaré reçoit son unique
 * argument dans {@code $_} ; une référence de méthode est appelée avec les arguments (FR-33b).
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

    /** Valeur du bloc convertie vers le type de retour de la méthode (FR-50). */
    private static Object result(Method sam, Object value) {
        Class<?> type = sam.getReturnType();
        if (type == void.class) {
            return null;
        }
        if (type == boolean.class && !(value instanceof Boolean)) {
            throw new PjException("le bloc doit renvoyer un booléen (" + sam.getDeclaringClass().getSimpleName()
                    + "." + sam.getName() + "), reçu " + Operators.describe(value));
        }
        if (JavaInvoker.cost(type, value) == JavaInvoker.NO_MATCH) {
            throw new PjException("le bloc renvoie " + Operators.describe(value) + ", "
                    + type.getSimpleName() + " attendu (" + sam.getDeclaringClass().getSimpleName() + "."
                    + sam.getName() + ")");
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

    /** Méthodes publiques d'{@code Object} redéclarées par une interface ({@code Comparator.equals}). */
    private static boolean isObjectMethod(Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException _) {
            return false;
        }
    }
}
