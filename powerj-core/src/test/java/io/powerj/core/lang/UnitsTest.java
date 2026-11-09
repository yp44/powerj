package io.powerj.core.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The unit literals (FR-19). The units of a size are the ones of {@code powerj-text} ({@code SizeUnitsTest}) and of the
 * filter of the grid ({@code FilterParserTest}): the same table of factors is checked in the three.
 */
class UnitsTest {

    @ParameterizedTest(name = "1{0} = {1} bytes")
    @CsvSource({
            "b,  1",
            "kb, 1024",
            "mb, 1048576",
            "gb, 1073741824",
            "tb, 1099511627776",
    })
    void aSizeLiteralIsInBytesAsALong(String unit, long bytes) {
        assertThat(Units.parse("1" + unit)).contains(bytes);
        assertThat(Units.parse("2" + unit)).contains(2 * bytes);
    }

    @ParameterizedTest(name = "1{0} is no literal: the case of a unit matters")
    @ValueSource(strings = {"KB", "Mb", "gB", "TB", "B", "MS", "S", "M", "H", "D", "Ms"})
    void aUnitInAnotherCaseIsNoLiteral(String unit) {
        assertThat(Units.parse("1" + unit)).isEmpty();
    }

    @ParameterizedTest(name = "{0} is {1} bytes")
    @CsvSource({"2.5mb, 2621440", "0.5kb, 512", "1.5b, 2", "0.4b, 0", "0.0004kb, 0", "1.5gb, 1610612736"})
    void aDecimalSizeIsRoundedToTheByte(String text, long bytes) {
        assertThat(Units.parse(text)).contains(bytes);
    }

    @ParameterizedTest(name = "\"{0}\" is no literal")
    @ValueSource(strings = {"1pb", "1kib", "1 kb", "kb", "-1kb", "1,5kb", "1.kb", ".5kb", "1kbb", ""})
    void whatIsNotAUnitIsNotALiteral(String text) {
        assertThat(Units.parse(text)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"250ms, 250000000", "30s, 30000000000", "5m, 300000000000", "2h, 7200000000000", "7d, 604800000000000"})
    void aDurationLiteralIsADuration(String text, long nanos) {
        assertThat(Units.parse(text)).contains(Duration.ofNanos(nanos));
    }
}
