package io.powerj.core.java;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.function.IntConsumer;

import io.powerj.core.error.PjException;
import io.powerj.core.i18n.Messages;

/**
 * The Java calls that would act on the shell itself rather than on the command (FR-58): ending the JVM, or taking the
 * standard streams away from the terminal. One policy per session.
 * <p>
 * Every call to Java code, whatever way it was written, goes through {@link JavaInvoker}, which asks the policy first:
 * {@code System.exit(1)}, the method reference {@code System::exit} (also when a stream calls it), a lambda that calls
 * it, or {@code Method.invoke} on the {@code exit} method. Review finding C-12: the interception used to be written
 * for the direct call only.
 * <ul>
 *   <li>{@code System.exit}, {@code Runtime.exit}, {@code Runtime.halt}: the same as the {@code exit} command, that is
 *       an exit request of the session, honoured when the line ends (clean shutdown, history saved);</li>
 *   <li>{@code System.setOut}, {@code setErr}, {@code setIn}: refused, they would break the display.</li>
 * </ul>
 * This guards against accidents. It is not a sandbox: code that goes through a {@code MethodHandle} or native code is
 * not seen, and a third-party module runs with the rights of the shell.
 */
public final class CallPolicy {

    private static final Set<String> REFUSED_SYSTEM_METHODS = Set.of("setOut", "setErr", "setIn");

    private final IntConsumer exitRequest;

    /** @param exitRequest receives the code of a {@code System.exit} typed at the prompt: the shell closes when the line ends */
    public CallPolicy(IntConsumer exitRequest) {
        this.exitRequest = exitRequest;
    }

    /**
     * Decides what happens to the call of {@code method}.
     *
     * @param method the Java method about to be called
     * @param target the object it is called on, {@code null} for a static method
     * @param args   the arguments, already converted
     * @return {@code true} if the policy took the call over (the caller returns {@code null}), {@code false} if the
     *         call may go on
     * @throws PjException if the call is refused
     */
    boolean intercept(Method method, Object target, Object[] args) {
        Class<?> owner = method.getDeclaringClass();
        String name = method.getName();
        if (owner == System.class && name.equals("exit") || owner == Runtime.class && (name.equals("exit")
                || name.equals("halt"))) {
            if (args.length == 1 && args[0] instanceof Integer code) {
                exit(code);
                return true;
            }
            return false; // reached through reflection with a wrong argument: the call itself will fail
        }
        if (owner == System.class && REFUSED_SYSTEM_METHODS.contains(name)) {
            throw new PjException(Messages.get("java.systemRefused", name));
        }
        if (owner == Method.class && name.equals("invoke") && target instanceof Method inner) {
            // Reflection: the call that matters is the one of the method being invoked.
            Object[] innerArgs = args.length > 1 && args[1] instanceof Object[] values ? values : new Object[0];
            return intercept(inner, args.length > 0 ? args[0] : null, innerArgs);
        }
        return false;
    }

    /** Equivalent to the command {@code exit code}. */
    private void exit(int code) {
        PreviewGuard.deny(Messages.get("preview.off.exit"));
        exitRequest.accept(code);
    }
}
