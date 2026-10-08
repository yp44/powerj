package io.powerj.core.exec;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import io.powerj.api.ScriptBlock;

/**
 * Method reference (FR-33b), resolved on each call according to the arguments received, as in Java:
 * <ul>
 *   <li>{@code Class::new}: constructor;</li>
 *   <li>{@code Class::method}: static method if it accepts this number of arguments, otherwise an instance
 *       method called on the first argument ({@code String::length});</li>
 *   <li>{@code $object::method}: instance method of this object;</li>
 *   <li>{@code Type::method} where {@code Type} is not a JDK class ({@code FileEntry::name}):
 *       instance method of the first argument, which must be of this type.</li>
 * </ul>
 */
sealed interface MethodReference extends ScriptBlock {

    /** Calls the method with the arguments received. */
    Object apply(Object[] args);

    @Override
    default Object invoke(Object current) {
        return apply(new Object[] {current});
    }

    @Override
    default String source() {
        return toString();
    }

    /** {@code $object::method}. */
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

    /** {@code Class::method} or {@code Class::new}, the class being known. */
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
            throw new PjException(args.length == 0 ? Messages.get("methodref.notApplicable.none", this)
                    : Messages.get("methodref.notApplicable", this, describe(args)));
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

    /** {@code Type::method}, the type being known only by its name (output of a cmdlet). */
    record ByTypeName(String typeName, String method) implements MethodReference {
        @Override
        public Object apply(Object[] args) {
            if (args.length == 0 || args[0] == null || !hasType(args[0].getClass())) {
                throw new PjException(args.length == 0 ? Messages.get("methodref.typeExpected.none", this, typeName)
                        : Messages.get("methodref.typeExpected", this, typeName, describe(args)));
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
        return String.join(", ", Arrays.stream(args).map(JavaInvoker::typeName).toList());
    }
}
