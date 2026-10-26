package io.powerj.core.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** How a value is named in a message. */
class ValuesTest {

    @Test
    void describeAbbreviatesLongValues() {
        assertThat(Values.describe(null)).isEqualTo("null");
        assertThat(Values.describe("x".repeat(40))).isEqualTo("String \"" + "x".repeat(27) + "…\"");
        assertThat(Values.describe(12)).isEqualTo("Integer 12");
        assertThat(Values.describe(Duration.ofSeconds(1))).isEqualTo("Duration 1.000 s");
    }
}
