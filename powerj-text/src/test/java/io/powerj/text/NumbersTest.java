package io.powerj.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void integersAreIntegralAndTheOnesThatFitALongSaySo() {
        for (Number n : new Number[] {1L, 2, (short) 3, (byte) 4, new AtomicInteger(5), new AtomicLong(6), new LongAdder()}) {
            assertThat(Numbers.isIntegral(n)).as(n.getClass().getSimpleName()).isTrue();
            assertThat(Numbers.fitsLong(n)).as(n.getClass().getSimpleName()).isTrue();
        }
        assertThat(Numbers.isIntegral(BigInteger.TEN)).isTrue();
        assertThat(Numbers.fitsLong(BigInteger.TEN)).isFalse(); // may not fit: a comparison by long would be wrong
        for (Number n : new Number[] {1.5, 1.5f, new BigDecimal("2"), new DoubleAdder()}) {
            assertThat(Numbers.isIntegral(n)).as(n.getClass().getSimpleName()).isFalse();
        }
    }

    @Test
    void nanAndInfinitiesHaveNoValue() {
        assertThat(Numbers.isFinite(Double.NaN)).isFalse();
        assertThat(Numbers.isFinite(Float.POSITIVE_INFINITY)).isFalse();
        assertThat(Numbers.isFinite(1.5)).isTrue();
        assertThat(Numbers.isFinite(5L)).isTrue();
        assertThat(Numbers.isFinite(BigDecimal.ONE)).isTrue();
        var adder = new DoubleAdder();
        adder.add(Double.NaN);
        assertThat(Numbers.isFinite(adder)).isFalse();
        assertThatThrownBy(() -> Numbers.decimal(adder)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Numbers.decimal(Double.NaN)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void theDecimalValueIsExactForIntegersAndThePrintedValueOtherwise() {
        assertThat(Numbers.decimal(7L)).isEqualByComparingTo("7");
        assertThat(Numbers.decimal(new BigInteger("123456789012345678901234567890")))
                .isEqualByComparingTo("123456789012345678901234567890");
        assertThat(Numbers.decimal(0.1)).isEqualByComparingTo("0.1");
        assertThat(Numbers.decimal(0.1f)).isEqualByComparingTo("0.1");
        assertThat(Numbers.decimal(new BigDecimal("1.50"))).isEqualByComparingTo("1.5");
    }

    @Test
    void anAdderKeepsItsFractionAndAnotherNumberIsWhatItPrints() {
        var adder = new DoubleAdder();
        adder.add(1.5);
        var longs = new LongAdder();
        longs.add(41);
        longs.increment();

        assertThat(Numbers.decimal(adder)).isEqualByComparingTo("1.5"); // it was 1: the value truncated to a long
        assertThat(Numbers.decimal(longs)).isEqualByComparingTo("42");
    }
}
