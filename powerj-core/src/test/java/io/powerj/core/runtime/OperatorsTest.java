package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import io.powerj.core.error.PjException;
import io.powerj.core.lang.Ast.Operator;

/** Semantics of the block operators (specification FR-33). */
class OperatorsTest {

    private static Object binary(Operator op, Object left, Object right) {
        return Operators.binary(op, left, right);
    }

    @Test
    void equalityComparesNumbersAndCharactersByValue() {
        assertThat(binary(Operator.EQ, 1, 1L)).isEqualTo(true);
        assertThat(binary(Operator.EQ, 1.0, 1)).isEqualTo(true);
        assertThat(binary(Operator.EQ, 'a', 97)).isEqualTo(true);
        assertThat(binary(Operator.EQ, 97L, 'a')).isEqualTo(true);
        assertThat(binary(Operator.EQ, new BigDecimal("2.50"), 2.5)).isEqualTo(true);
        assertThat(binary(Operator.EQ, "a", "a")).isEqualTo(true);
        assertThat(binary(Operator.EQ, null, null)).isEqualTo(true);
        assertThat(binary(Operator.NE, "a", null)).isEqualTo(true);
        assertThat(binary(Operator.NE, 2, 'a')).isEqualTo(true);
    }

    @Test
    void comparisonsOfNumbersAndComparables() {
        assertThat(binary(Operator.LT, 1, 2L)).isEqualTo(true);
        assertThat(binary(Operator.LE, 2, 2.0)).isEqualTo(true);
        assertThat(binary(Operator.LE, 3, 2)).isEqualTo(false);
        assertThat(binary(Operator.GT, 'b', 'a')).isEqualTo(true);
        assertThat(binary(Operator.GE, new BigInteger("10"), 10)).isEqualTo(true);
        assertThat(binary(Operator.GT, 2.5f, 2)).isEqualTo(true);
        assertThat(binary(Operator.LT, "apple", "banana")).isEqualTo(true);
        assertThat(binary(Operator.GT, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1))).isEqualTo(true);
    }

    @Test
    void comparingUnrelatedValuesIsAnError() {
        assertThatThrownBy(() -> binary(Operator.LT, "a", 1))
                .isInstanceOf(PjException.class)
                .hasMessage("\"<\" is not possible between String \"a\" and Integer 1");
        assertThatThrownBy(() -> binary(Operator.GE, LocalDate.of(2026, 1, 1), null))
                .hasMessage("\">=\" is not possible between LocalDate 2026-01-01 and null");
        assertThatThrownBy(() -> binary(Operator.GT, new Object(), new Object()))
                .isInstanceOf(PjException.class);
    }

    @Test
    void additionOfTextsDatesAndDurations() {
        assertThat(binary(Operator.ADD, "n=", 3)).isEqualTo("n=3");
        assertThat(binary(Operator.ADD, null, "x")).isEqualTo("nullx");
        Instant epoch = Instant.EPOCH;
        assertThat(binary(Operator.ADD, epoch, Duration.ofSeconds(5))).isEqualTo(epoch.plusSeconds(5));
        assertThat(binary(Operator.ADD, Duration.ofSeconds(5), epoch)).isEqualTo(epoch.plusSeconds(5));
        assertThat(binary(Operator.ADD, Duration.ofSeconds(5), Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void subtractionOfDatesAndDurations() {
        LocalDateTime noon = LocalDateTime.of(2026, 1, 1, 12, 0);
        assertThat(binary(Operator.SUB, noon, Duration.ofHours(2))).isEqualTo(noon.minusHours(2));
        assertThat(binary(Operator.SUB, Duration.ofSeconds(5), Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(3));
        assertThat(binary(Operator.SUB, 5, 7)).isEqualTo(-2);
    }

    @Test
    void aDurationThatTheDateCannotTakeIsAnError() {
        assertThatThrownBy(() -> binary(Operator.ADD, LocalDate.of(2026, 1, 1), Duration.ofHours(1)))
                .isInstanceOf(PjException.class)
                .hasMessageStartingWith("cannot add a duration to LocalDate 2026-01-01: ");
    }

    @Test
    void integerArithmeticFollowsJava() {
        assertThat(binary(Operator.ADD, 2, 3)).isEqualTo(5);
        assertThat(binary(Operator.MUL, 4, 3)).isEqualTo(12);
        assertThat(binary(Operator.DIV, 7, 2)).isEqualTo(3);
        assertThat(binary(Operator.REM, 7, 2)).isEqualTo(1);
        assertThat(binary(Operator.ADD, 'a', 1)).isEqualTo(98);
    }

    @Test
    void longArithmeticAsSoonAsOneOperandIsLong() {
        assertThat(binary(Operator.ADD, 2L, 3)).isEqualTo(5L);
        assertThat(binary(Operator.SUB, 2, 3L)).isEqualTo(-1L);
        assertThat(binary(Operator.MUL, 4L, 3L)).isEqualTo(12L);
        assertThat(binary(Operator.DIV, 7L, 2)).isEqualTo(3L);
        assertThat(binary(Operator.REM, 7L, 2)).isEqualTo(1L);
    }

    @Test
    void floatingArithmeticAsSoonAsOneOperandIsDecimal() {
        assertThat(binary(Operator.ADD, 1.5, 1)).isEqualTo(2.5);
        assertThat(binary(Operator.SUB, 1, 0.5f)).isEqualTo(0.5);
        assertThat(binary(Operator.MUL, 1.5, 2)).isEqualTo(3.0);
        assertThat(binary(Operator.DIV, 1, 4.0)).isEqualTo(0.25);
        assertThat(binary(Operator.REM, 7.5, 2)).isEqualTo(1.5);
    }

    @Test
    void bigNumbersKeepTheirPrecision() {
        assertThat(binary(Operator.ADD, new BigDecimal("0.1"), 0.2)).isEqualTo(new BigDecimal("0.3"));
        assertThat(binary(Operator.SUB, new BigDecimal("1.5"), 1)).isEqualTo(new BigDecimal("0.5"));
        assertThat(binary(Operator.MUL, new BigDecimal("1.5"), 2L)).isEqualTo(new BigDecimal("3.0"));
        assertThat(binary(Operator.DIV, 1, new BigDecimal("4"))).isEqualTo(new BigDecimal("0.25"));
        assertThat(binary(Operator.REM, new BigDecimal("7"), 2)).isEqualTo(new BigDecimal("1"));
        assertThat(binary(Operator.ADD, new BigInteger("2"), 1.5f)).isEqualTo(new BigDecimal("3.5"));

        BigInteger big = new BigInteger("100000000000000000000");
        assertThat(binary(Operator.ADD, big, 1)).isEqualTo(big.add(BigInteger.ONE));
        assertThat(binary(Operator.SUB, 1L, big)).isEqualTo(BigInteger.ONE.subtract(big));
        assertThat(binary(Operator.MUL, big, 2)).isEqualTo(big.shiftLeft(1));
        assertThat(binary(Operator.DIV, big, big)).isEqualTo(BigInteger.ONE);
        assertThat(binary(Operator.REM, big, 3)).isEqualTo(big.remainder(BigInteger.valueOf(3)));
    }

    @Test
    void arithmeticErrors() {
        assertThatThrownBy(() -> binary(Operator.DIV, 1, 0))
                .isInstanceOf(PjException.class)
                .hasMessage("calculation failed: / by zero");
        assertThatThrownBy(() -> binary(Operator.MUL, "a", 2))
                .hasMessage("\"*\" is not possible between String \"a\" and Integer 2");
        assertThatThrownBy(() -> binary(Operator.SUB, true, 1))
                .hasMessage("\"-\" is not possible between Boolean true and Integer 1");
    }

    @Test
    void divisionByZeroIsNamedEvenOnceCompiled() {
        // Many divisions by zero let the JIT replace the exception by a preallocated one, without message.
        for (int i = 0; i < 100_000; i++) {
            assertThatThrownBy(() -> binary(Operator.DIV, 1, 0)).hasMessage("calculation failed: / by zero");
        }
    }

    @Test
    void logicalOperatorsAreNotBinaryValues() {
        assertThatThrownBy(() -> binary(Operator.AND, true, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Operators.unary(Operator.ADD, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unaryOperators() {
        assertThat(Operators.unary(Operator.NOT, false)).isEqualTo(true);
        assertThat(Operators.unary(Operator.NEG, 3)).isEqualTo(-3);
        assertThat(Operators.unary(Operator.NEG, 2.5)).isEqualTo(-2.5);
        assertThat(Operators.unary(Operator.NEG, 'a')).isEqualTo(-97);
        assertThat(Operators.unary(Operator.NEG, Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(-2));
        assertThatThrownBy(() -> Operators.unary(Operator.NEG, "a"))
                .hasMessage("\"-\" is not possible on String \"a\"");
        assertThatThrownBy(() -> Operators.unary(Operator.NEG, null))
                .hasMessage("\"-\" is not possible on null");
        assertThatThrownBy(() -> Operators.unary(Operator.NOT, 1))
                .hasMessage("\"!\" expects a boolean, got Integer 1");
    }
}
