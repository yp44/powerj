package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.powerj.api.UsageException;

class CountsTest {

    @Test
    void anAbsentCountIsOne() {
        assertThat(Counts.orOne(null)).isEqualTo(1);
    }

    @Test
    void aGivenCountIsKeptZeroIncluded() {
        assertThat(Counts.orOne(0)).isZero();
        assertThat(Counts.orOne(7)).isEqualTo(7);
    }

    @Test
    void aNegativeCountIsAUsageError() {
        assertThatThrownBy(() -> Counts.orOne(-1)).isInstanceOf(UsageException.class).hasMessage("expects a number of objects of at least 0, got -1");
    }
}
