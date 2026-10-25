package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.error.PjException;
import io.powerj.core.runtime.Session;

/**
 * The Java calls that act on the shell itself (FR-58; review C-12). The decision is taken in one place, so each way of
 * reaching {@code System.exit} is tested against {@link JavaInvoker}, which every call goes through.
 */
class CallPolicyTest {

    @TempDir
    Path tmp;

    private Session session;
    private CallPolicy policy;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, Map.of("PATH", ""));
        policy = new CallPolicy(session);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        return type.getMethod(name, parameters);
    }

    @Test
    void exitMethodsBecomeAnExitRequest() throws Exception {
        assertThat(policy.intercept(method(System.class, "exit", int.class), null, new Object[] {3})).isTrue();
        assertThat(session.exitRequest()).hasValue(3);

        assertThat(policy.intercept(method(Runtime.class, "exit", int.class), Runtime.getRuntime(), new Object[] {4}))
                .isTrue();
        assertThat(session.exitRequest()).hasValue(4);

        assertThat(policy.intercept(method(Runtime.class, "halt", int.class), Runtime.getRuntime(), new Object[] {5}))
                .isTrue();
        assertThat(session.exitRequest()).hasValue(5);
    }

    @Test
    void theStandardStreamsCannotBeReplaced() throws Exception {
        assertThatThrownBy(() -> policy.intercept(method(System.class, "setOut", java.io.PrintStream.class), null,
                new Object[] {null})).isInstanceOf(PjException.class)
                .hasMessage("System.setOut is refused: it would break the shell display");
        assertThatThrownBy(() -> policy.intercept(method(System.class, "setIn", java.io.InputStream.class), null,
                new Object[] {null})).hasMessage("System.setIn is refused: it would break the shell display");
        assertThatThrownBy(() -> policy.intercept(method(System.class, "setErr", java.io.PrintStream.class), null,
                new Object[] {null})).hasMessage("System.setErr is refused: it would break the shell display");
    }

    @Test
    void otherCallsGoOn() throws Exception {
        assertThat(policy.intercept(method(Math.class, "abs", int.class), null, new Object[] {-1})).isFalse();
        assertThat(policy.intercept(method(System.class, "lineSeparator"), null, new Object[0])).isFalse();
        assertThat(policy.intercept(method(Runtime.class, "availableProcessors"), Runtime.getRuntime(), new Object[0]))
                .isFalse();
        assertThat(session.exitRequest()).isEmpty();
    }

    @Test
    void reflectionIsSeenThrough() throws Exception {
        Method invoke = method(Method.class, "invoke", Object.class, Object[].class);
        Method exit = method(System.class, "exit", int.class);
        Method setOut = method(System.class, "setOut", java.io.PrintStream.class);

        assertThat(policy.intercept(invoke, exit, new Object[] {null, new Object[] {9}})).isTrue();
        assertThat(session.exitRequest()).hasValue(9);
        assertThatThrownBy(() -> policy.intercept(invoke, setOut, new Object[] {null, new Object[] {null}}))
                .hasMessageContaining("System.setOut is refused");
        assertThat(policy.intercept(invoke, method(Math.class, "abs", int.class), new Object[] {null, new Object[] {1}}))
                .isFalse();
    }

    @Test
    void reflectionWithAWrongArgumentIsLeftToFailByItself() throws Exception {
        Method invoke = method(Method.class, "invoke", Object.class, Object[].class);
        Method exit = method(System.class, "exit", int.class);

        // Method.invoke checks the argument types before it runs anything: the JVM does not end.
        assertThat(policy.intercept(invoke, exit, new Object[] {null, new Object[] {"x"}})).isFalse();
        assertThat(policy.intercept(invoke, exit, new Object[] {null, new Object[0]})).isFalse();
        assertThat(policy.intercept(invoke, exit, new Object[0])).isFalse();
        assertThat(session.exitRequest()).isEmpty();
    }

    // --- Through JavaInvoker: all the ways of writing the call reach the policy ---

    @Test
    void aStaticCallThroughTheInvokerIsIntercepted() {
        assertThat(JavaInvoker.invokeStatic(policy, System.class, "exit", List.of(2))).isNull();
        assertThat(session.exitRequest()).hasValue(2);
    }

    @Test
    void aVirtualCallThroughTheInvokerIsIntercepted() {
        assertThat(JavaInvoker.invokeVirtual(policy, Runtime.getRuntime(), "halt", List.of(6))).isNull();
        assertThat(session.exitRequest()).hasValue(6);
    }

    @Test
    void aMethodReferenceIsIntercepted() {
        var reference = new MethodReference.OfClass(System.class, "exit", policy);

        assertThat(reference.apply(new Object[] {8})).isNull();
        assertThat(session.exitRequest()).hasValue(8);
    }

    @Test
    void aBoundMethodReferenceIsIntercepted() {
        var reference = new MethodReference.Bound(Runtime.getRuntime(), "exit", policy);

        assertThat(reference.apply(new Object[] {11})).isNull();
        assertThat(session.exitRequest()).hasValue(11);
    }

    @Test
    void reflectionThroughTheInvokerIsIntercepted() throws Exception {
        Method exit = method(System.class, "exit", int.class);

        assertThat(JavaInvoker.invokeVirtual(policy, exit, "invoke", java.util.Arrays.asList(null, 12))).isNull();
        assertThat(session.exitRequest()).hasValue(12);
    }
}
