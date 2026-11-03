package io.powerj.core.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import io.powerj.api.internal.LanguageSetting;

/** How a value is named in a message. */
class ValuesTest {

    @Test
    void describeAbbreviatesLongValues() {
        assertThat(Values.describe(null)).isEqualTo("null");
        assertThat(Values.describe("x".repeat(40))).isEqualTo("String \"" + "x".repeat(27) + "…\"");
        assertThat(Values.describe(12)).isEqualTo("Integer 12");
        assertThat(Values.describe(Duration.ofSeconds(1))).isEqualTo("Duration 1.000 s");
    }

    @Test
    void aDurationIsWrittenTheSameInEveryLanguage() {
        // the same table as FormatsTest (powerj-text) and CellFormatterTest (fx): terminal and grid agree (#97)
        for (Locale language : new Locale[] {Locale.ENGLISH, Locale.FRENCH}) {
            LanguageSetting.set(language);
            try {
                assertThat(Values.text(Duration.ZERO)).as(language.toString()).isEqualTo("0 ns");
                assertThat(Values.text(Duration.ofNanos(2_000))).isEqualTo("2.0 µs");
                assertThat(Values.text(Duration.ofMillis(10))).isEqualTo("0.010 s");
                assertThat(Values.text(Duration.ofMillis(1_500))).isEqualTo("1.500 s");
                assertThat(Values.text(Duration.ofSeconds(65))).isEqualTo("1 min 05 s");
                assertThat(Values.text(Duration.ofSeconds(90))).isEqualTo("1 min 30 s");
                assertThat(Values.text(Duration.ofMinutes(90))).isEqualTo("90 min 00 s");
            } finally {
                LanguageSetting.set(Locale.ENGLISH);
            }
        }
    }
}
