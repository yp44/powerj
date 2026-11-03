package io.powerj.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.powerj.api.internal.LanguageSetting;

/**
 * The text of a size, a duration and a date (#109), the same in the terminal, the grid and the exports. The same table
 * is checked in {@code core} and in {@code fx}, which call this class: that is what keeps them from disagreeing (#97).
 */
class FormatsTest {

    @AfterEach
    void english() {
        LanguageSetting.set(Locale.ENGLISH);
    }

    static Object[][] durations() {
        return new Object[][] {
            {Duration.ZERO, "0 ns"},
            {Duration.ofNanos(150), "150 ns"},
            {Duration.ofNanos(1_400), "1.4 µs"},
            {Duration.ofNanos(2_000), "2.0 µs"},
            {Duration.ofMillis(1), "0.001 s"},
            {Duration.ofMillis(10), "0.010 s"},
            {Duration.ofMillis(1_500), "1.500 s"},
            {Duration.ofSeconds(59), "59.000 s"},
            {Duration.ofSeconds(60), "1 min 00 s"},
            {Duration.ofSeconds(65), "1 min 05 s"},
            {Duration.ofSeconds(90), "1 min 30 s"},
            {Duration.ofMinutes(90), "90 min 00 s"},
            {Duration.ofHours(1), "60 min 00 s"},
        };
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("durations")
    void aDurationIsWrittenAsTheTerminalAlwaysDid(Duration duration, String expected) {
        assertThat(Formats.duration(duration)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}, in French too")
    @MethodSource("durations")
    void aDurationIsTheSameTextInFrenchWithTheDecimalPoint(Duration duration, String expected) {
        LanguageSetting.set(Locale.FRENCH);

        // a script that interpolates a duration must see one text whatever the language of the machine
        assertThat(Formats.duration(duration)).isEqualTo(expected);
    }

    static Object[][] sizes() {
        return new Object[][] {
            {0L, "0 B", "0 B"},
            {512L, "512 B", "512 B"},
            {1_023L, "1023 B", "1023 B"},
            {1_024L, "1.0 KB", "1,0 KB"},
            {14_520L, "14.2 KB", "14,2 KB"},
            {1_024L * 1_024, "1.0 MB", "1,0 MB"},
            {5L * 1_024 * 1_024 * 1_024, "5.0 GB", "5,0 GB"},
            {1L << 40, "1.0 TB", "1,0 TB"},
            {1L << 50, "1.0 PB", "1,0 PB"},
            {Long.MAX_VALUE, "8192.0 PB", "8192,0 PB"},
        };
    }

    @ParameterizedTest(name = "{0} → {1} / {2}")
    @MethodSource("sizes")
    void aSizeFollowsTheDecimalSeparatorOfTheLanguage(long bytes, String english, String french) {
        assertThat(Formats.bytes(bytes)).isEqualTo(english);
        LanguageSetting.set(Locale.FRENCH);
        assertThat(Formats.bytes(bytes)).isEqualTo(french);
    }

    @Test
    void anInstantIsALocalDateAndTime() {
        Instant instant = Instant.parse("2026-10-05T18:12:30Z");
        String expected = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(instant.atZone(ZoneId.systemDefault()));

        assertThat(Formats.dateTime(instant)).isEqualTo(expected).matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:12");
    }
}
