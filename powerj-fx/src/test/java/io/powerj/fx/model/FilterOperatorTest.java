package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class FilterOperatorTest {

    @Test
    void theListOffersTheFiveComparisonsInOrder() {
        assertThat(Arrays.stream(FilterOperator.values()).map(FilterOperator::symbol))
                .containsExactly("==", "<", ">", "<=", ">=");
        assertThat(FilterOperator.GREATER_OR_EQUAL).hasToString(">=");
        assertThat(FilterOperator.EQUAL).hasToString("==");
    }

    @Test
    void aPlainValueTakesTheOperator() {
        assertThat(FilterOperator.EQUAL.apply("228")).isEqualTo("=228");
        assertThat(FilterOperator.LESS.apply("10kb")).isEqualTo("<10kb");
        assertThat(FilterOperator.GREATER.apply("2.2kb")).isEqualTo(">2.2kb");
        assertThat(FilterOperator.LESS_OR_EQUAL.apply(" 5 ")).isEqualTo("<=5");
        assertThat(FilterOperator.GREATER_OR_EQUAL.apply("2026-10")).isEqualTo(">=2026-10");
    }

    @Test
    void aTextWithItsOwnOperatorIsTakenAsTyped() {
        for (FilterOperator operator : FilterOperator.values()) {
            for (String text : new String[] {">5", "<5", ">=5", "<=5", "=5", "!5", "1..9", "5..", "..9", "/^2/"}) {
                assertThat(operator.apply(text)).as(operator + " " + text).isEqualTo(text);
            }
        }
    }

    @Test
    void nothingTypedIsNothing() {
        for (FilterOperator operator : FilterOperator.values()) {
            assertThat(operator.apply("")).isEmpty();
            assertThat(operator.apply("   ")).isEmpty();
            assertThat(operator.apply(null)).isEmpty();
        }
    }
}
