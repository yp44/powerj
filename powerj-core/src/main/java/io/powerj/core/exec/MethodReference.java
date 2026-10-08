package io.powerj.core.exec;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import io.powerj.api.ScriptBlock;

/**
 * Référence de méthode (FR-33b), résolue à chaque appel selon les arguments reçus, comme en Java :
 * <ul>
 *   <li>{@code Classe::new} : constructeur ;</li>
 *   <li>{@code Classe::méthode} : méthode statique si elle accepte ce nombre d'arguments, sinon méthode
 *       d'instance appelée sur le premier argument ({@code String::length}) ;</li>
 *   <li>{@code $objet::méthode} : méthode d'instance de cet objet ;</li>
 *   <li>{@code Type::méthode} où {@code Type} n'est pas une classe du JDK ({@code FileEntry::name}) :
 *       méthode d'instance du premier argument, qui doit être de ce type.</li>
 * </ul>
 */
sealed interface MethodReference extends ScriptBlock {

    /** Appelle la méthode avec les arguments reçus. */
    Object apply(Object[] args);

    @Override
    default Object invoke(Object current) {
        return apply(new Object[] {current});
    }

    @Override
    default String source() {
        return toString();
    }

    /** {@code $objet::méthode}. */
    record Bound(Object target, String method) implements MethodReference {
        @Override
        public Object apply(Object[] args) {
            return JavaInvoker.invokeVirtual(target, method, Arrays.asList(args));
        }

        @Override
        public String toString() {
            return target.getClass().getSimpleName() + "::" + method;
        }
    }

    /** {@code Classe::méthode} ou {@code Classe::new}, la classe étant connue. */
    record OfClass(Class<?> type, String method) implements MethodReference {
        @Override
        public Object apply(Object[] args) {
            List<Object> all = Arrays.asList(args);
            if (method.equals("new")) {
                return JavaInvoker.construct(type, all);
            }
            if (hasStatic(args.length)) {
                return JavaInvoker.invokeStatic(type, method, all);
            }
            if (args.length > 0 && type.isInstance(args[0])) {
                return JavaInvoker.invokeVirtual(args[0], method, all.subList(1, all.size()));
            }
            throw new PjException(this + " : ne s'applique pas à " + describe(args));
        }

        private boolean hasStatic(int arity) {
            for (Method m : type.getMethods()) {
                if (m.getName().equals(method) && Modifier.isStatic(m.getModifiers())
                        && (m.getParameterCount() == arity || m.isVarArgs() && arity >= m.getParameterCount() - 1)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return type.getSimpleName() + "::" + method;
        }
    }

    /** {@code Type::méthode}, le type n'étant connu que par son nom (sortie d'un cmdlet). */
    record ByTypeName(String typeName, String method) implements MethodReference {
        @Override
        public Object apply(Object[] args) {
            if (args.length == 0 || args[0] == null || !hasType(args[0].getClass())) {
                throw new PjException(this + " : " + typeName + " attendu, reçu " + describe(args));
            }
            List<Object> all = Arrays.asList(args);
            return JavaInvoker.invokeVirtual(args[0], method, all.subList(1, all.size()));
        }

        private boolean hasType(Class<?> type) {
            for (Class<?> t = type; t != null; t = t.getSuperclass()) {
                if (t.getSimpleName().equals(typeName)) {
                    return true;
                }
                for (Class<?> i : t.getInterfaces()) {
                    if (i.getSimpleName().equals(typeName)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return typeName + "::" + method;
        }
    }

    private static String describe(Object[] args) {
        return args.length == 0 ? "aucun argument"
                : String.join(", ", Arrays.stream(args).map(JavaInvoker::typeName).toList());
    }
}
