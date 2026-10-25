package io.powerj.core.java;

import static io.powerj.core.java.JavaInvoker.NO_MATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

import io.powerj.api.ScriptBlock;
import io.powerj.core.error.PjException;
import io.powerj.core.runtime.Session;
import io.powerj.core.support.InvokerFixture;

/** Java calls: overload selection and argument conversions (FR-48 to FR-50, FR-53). */
class JavaInvokerTest {

    private static final CallPolicy POLICY = new CallPolicy(
            new Session(Path.of("").toAbsolutePath(), Path.of("").toAbsolutePath(), Map.of()));

    private static final ScriptBlock BLOCK = new ScriptBlock() {
        @Override
        public Object invoke(Object current) {
            return current;
        }

        @Override
        public String source() {
            return "$_";
        }
    };

    @Test
    void costOfNullAndOfExactTypes() {
        assertThat(JavaInvoker.cost(int.class, null)).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(String.class, null)).isEqualTo(2);
        assertThat(JavaInvoker.cost(Integer.class, 1)).isZero();
        assertThat(JavaInvoker.cost(int.class, 1)).isEqualTo(1);
        assertThat(JavaInvoker.cost(Number.class, 1)).isEqualTo(2);
        assertThat(JavaInvoker.cost(Thread.class, new Object())).isEqualTo(NO_MATCH);
    }

    @Test
    void costOfCharacters() {
        assertThat(JavaInvoker.cost(int.class, 'a')).isEqualTo(4);
        assertThat(JavaInvoker.cost(byte.class, 'a')).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(String.class, 'a')).isEqualTo(9);
        assertThat(JavaInvoker.cost(CharSequence.class, 'a')).isEqualTo(9);
        assertThat(JavaInvoker.cost(Boolean.class, 'a')).isEqualTo(NO_MATCH);
    }

    @Test
    void costOfNumbers() {
        assertThat(JavaInvoker.cost(long.class, 1)).isEqualTo(4);
        assertThat(JavaInvoker.cost(int.class, 5L)).isEqualTo(9);
        assertThat(JavaInvoker.cost(int.class, Long.MAX_VALUE)).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(short.class, 5)).isEqualTo(9);
        assertThat(JavaInvoker.cost(byte.class, 300)).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(byte.class, (short) 1)).isEqualTo(9);
        assertThat(JavaInvoker.cost(long.class, BigInteger.ONE)).isEqualTo(9);
        assertThat(JavaInvoker.cost(int.class, 1.5)).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(float.class, 1.5)).isEqualTo(9);
        assertThat(JavaInvoker.cost(float.class, BigDecimal.ONE)).isEqualTo(9);
        assertThat(JavaInvoker.cost(double.class, BigDecimal.ONE)).isEqualTo(9);
        assertThat(JavaInvoker.cost(double.class, BigInteger.ONE)).isEqualTo(9);
        assertThat(JavaInvoker.cost(BigInteger.class, 1)).isEqualTo(8);
        assertThat(JavaInvoker.cost(BigInteger.class, 1.5)).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(BigDecimal.class, 1.5)).isEqualTo(8);
        assertThat(JavaInvoker.cost(String.class, 1)).isEqualTo(NO_MATCH);
    }

    @Test
    void costOfStringsBlocksAndCollections() {
        assertThat(JavaInvoker.cost(char.class, "a")).isEqualTo(6);
        assertThat(JavaInvoker.cost(char.class, "ab")).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(Path.class, "x")).isEqualTo(7);
        assertThat(JavaInvoker.cost(File.class, "x")).isEqualTo(7);
        assertThat(JavaInvoker.cost(DayOfWeek.class, "MONDAY")).isEqualTo(7);
        assertThat(JavaInvoker.cost(DayOfWeek.class, "monday")).isEqualTo(7);
        assertThat(JavaInvoker.cost(DayOfWeek.class, "someday")).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(Integer.class, "1")).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(Runnable.class, BLOCK)).isEqualTo(6);
        assertThat(JavaInvoker.cost(String.class, BLOCK)).isEqualTo(NO_MATCH);
        assertThat(JavaInvoker.cost(int[].class, List.of(1, 2))).isEqualTo(7);
        assertThat(JavaInvoker.cost(int[].class, List.of("a"))).isEqualTo(NO_MATCH);
    }

    @Test
    void conversions() {
        assertThat(JavaInvoker.convert(String.class, null)).isNull();
        assertThat(JavaInvoker.convert(int.class, 'a')).isEqualTo(97);
        assertThat(JavaInvoker.convert(String.class, 'a')).isEqualTo("a");
        assertThat(JavaInvoker.convert(short.class, 5)).isEqualTo((short) 5);
        assertThat(JavaInvoker.convert(byte.class, 5)).isEqualTo((byte) 5);
        assertThat(JavaInvoker.convert(float.class, 1.5)).isEqualTo(1.5f);
        assertThat(JavaInvoker.convert(long.class, 5)).isEqualTo(5L);
        assertThat(JavaInvoker.convert(double.class, 5)).isEqualTo(5.0);
        assertThat(JavaInvoker.convert(BigInteger.class, 5)).isEqualTo(BigInteger.valueOf(5));
        assertThat(JavaInvoker.convert(BigDecimal.class, 5)).isEqualTo(new BigDecimal("5"));
        assertThat(JavaInvoker.convert(BigDecimal.class, 2.5)).isEqualTo(new BigDecimal("2.5"));
        assertThat(JavaInvoker.convert(Character.class, 5)).isEqualTo(5);
        assertThat(JavaInvoker.convert(char.class, "a")).isEqualTo('a');
        assertThat(JavaInvoker.convert(Path.class, "x")).isEqualTo(Path.of("x"));
        assertThat(JavaInvoker.convert(File.class, "x")).isEqualTo(new File("x"));
        assertThat(JavaInvoker.convert(DayOfWeek.class, "monday")).isEqualTo(DayOfWeek.MONDAY);
        assertThat((int[]) JavaInvoker.convert(int[].class, List.of(1, 2L))).containsExactly(1, 2);
        assertThat(JavaInvoker.convert(Runnable.class, BLOCK)).isInstanceOf(Runnable.class);
        assertThatThrownBy(() -> JavaInvoker.convert(Thread.class, "x"))
                .isInstanceOf(PjException.class)
                .hasMessage("cannot convert String to Thread");
    }

    @Test
    void boxingSignaturesAndTypeNames() throws Exception {
        assertThat(JavaInvoker.box(byte.class)).isEqualTo(Byte.class);
        assertThat(JavaInvoker.box(short.class)).isEqualTo(Short.class);
        assertThat(JavaInvoker.box(void.class)).isEqualTo(Void.class);
        assertThat(JavaInvoker.box(String.class)).isEqualTo(String.class);
        assertThat(JavaInvoker.signature(String.class.getMethod("format", String.class, Object[].class)))
                .isEqualTo("format(String, Object...)");
        assertThat(JavaInvoker.signature(StringBuilder.class.getConstructor(String.class)))
                .isEqualTo("StringBuilder(String)");
        assertThat(JavaInvoker.typeName(null)).isEqualTo("null");
        assertThat(JavaInvoker.typeName(1L)).isEqualTo("Long");
    }

    @Test
    void instanceCalls() {
        assertThat(JavaInvoker.invokeVirtual(POLICY, "abc", "charAt", List.of(1))).isEqualTo('b');
        assertThat(JavaInvoker.hasNoArgMethod(String.class, "length")).isTrue();
        assertThat(JavaInvoker.hasNoArgMethod(String.class, "charAt")).isFalse();
        assertThatThrownBy(() -> JavaInvoker.invokeVirtual(POLICY, null, "length", List.of()))
                .isInstanceOf(PjException.class)
                .hasMessage("call of length() on a null value");
        assertThatThrownBy(() -> JavaInvoker.invokeVirtual(POLICY, "abc", "nope", List.of()))
                .hasMessage("String has no method nope()");
        assertThatThrownBy(() -> JavaInvoker.invokeVirtual(POLICY, "abc", "charAt", List.of("x")))
                .hasMessage("no overload of charAt matches the arguments (String); available: charAt(int)");
    }

    @Test
    void aTieBetweenUnrelatedOverloadsIsAmbiguous() {
        assertThatThrownBy(() -> JavaInvoker.invokeVirtual(POLICY, new StringBuilder(), "append", Arrays.asList((Object) null)))
                .isInstanceOf(PjException.class)
                .hasMessageStartingWith("ambiguous call of append: ");
    }

    @Test
    void aTieIsBrokenByTheMostSpecificOverload() {
        var fixture = new InvokerFixture();
        assertThat(JavaInvoker.invokeVirtual(POLICY, fixture, "join", List.of("a"))).isEqualTo("first:a+0");
        assertThat(JavaInvoker.invokeVirtual(POLICY, fixture, "letter", List.of("x"))).isEqualTo("char:x");
        assertThat(JavaInvoker.invokeStatic(POLICY, Math.class, "abs", List.of('a'))).isEqualTo(97);
    }

    @Test
    void staticCalls() {
        assertThat(JavaInvoker.invokeStatic(POLICY, String.class, "format", List.of("%s-%s", "a", "b"))).isEqualTo("a-b");
        assertThat(JavaInvoker.invokeStatic(POLICY, LinkedHashMap.class, "newHashMap", List.of(4))).isInstanceOf(HashMap.class);
        assertThatThrownBy(() -> JavaInvoker.invokeStatic(POLICY, Math.class, "nope", List.of()))
                .hasMessage("Math has no static method nope()");
        assertThatThrownBy(() -> JavaInvoker.invokeStatic(POLICY, Integer.class, "parseInt", List.of("x")))
                .isInstanceOf(PjException.class)
                .hasMessage("java.lang.NumberFormatException: For input string: \"x\"");
    }

    @Test
    void constructors() {
        assertThat(JavaInvoker.construct(StringBuilder.class, List.of("x")).toString()).isEqualTo("x");
        assertThatThrownBy(() -> JavaInvoker.construct(List.class, List.of()))
                .hasMessage("new List: abstract class or interface");
        assertThatThrownBy(() -> JavaInvoker.construct(AbstractList.class, List.of()))
                .hasMessage("new AbstractList: abstract class or interface");
        assertThatThrownBy(() -> JavaInvoker.construct(Math.class, List.of()))
                .hasMessage("new Math: no public constructor");
        assertThatThrownBy(() -> JavaInvoker.construct(URI.class, List.of("a b")))
                .isInstanceOf(PjException.class)
                .hasMessageStartingWith("java.net.URISyntaxException: ");
    }

    @Test
    void javaExceptionsBecomeShortErrors() {
        assertThat(JavaInvoker.javaException(new IllegalStateException())).hasMessage("java.lang.IllegalStateException");
        var error = new PjException("already a shell error");
        assertThat(JavaInvoker.javaException(error)).isSameAs(error);
        var cancelled = new CancellationException();
        assertThat(JavaInvoker.javaException(cancelled)).isSameAs(cancelled);
    }
}
