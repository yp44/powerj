package io.powerj.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The units of a size (#110): {@code b kb mb gb tb}, multiples of 1024, in any case. The language ({@code 2kb}) and the
 * filter of a column of the grid ({@code >2kb}) read their numbers their own way and take their units from here; the same
 * table is checked in {@code core} ({@code UnitsTest}) and in {@code fx} ({@code FilterParserTest}).
 */
class SizeUnitsTest {

    @ParameterizedTest(name = "{0} = 1024^{1} = {2} bytes")
    @CsvSource({
            "b,   0, 1",
            "kb,  1, 1024",
            "mb,  2, 1048576",
            "gb,  3, 1073741824",
            "tb,  4, 1099511627776",
            "KB,  1, 1024",
            "Mb,  2, 1048576",
            "gB,  3, 1073741824",
            "TB,  4, 1099511627776",
    })
    void eachUnitIsAPowerOf1024InAnyCase(String unit, int power, String bytes) {
        assertThat(SizeUnits.isUnit(unit)).isTrue();
        assertThat(SizeUnits.power(unit)).isEqualTo(power);
        assertThat(SizeUnits.factor(unit)).isEqualByComparingTo(new BigDecimal(bytes));
    }

    @ParameterizedTest(name = "\"{0}\" is not a unit of a size")
    @ValueSource(strings = {"", " ", "k", "pb", "kib", "KiB", "bytes", "s", "ms", "m", "h", "d", "kb ", " kb"})
    void anythingElseIsNotAUnit(String text) {
        assertThat(SizeUnits.isUnit(text)).isFalse();
        assertThat(SizeUnits.power(text)).isEqualTo(-1);
        assertThatThrownBy(() -> SizeUnits.factor(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIsNotAUnit() {
        assertThat(SizeUnits.isUnit(null)).isFalse();
        assertThat(SizeUnits.power(null)).isEqualTo(-1);
    }

    @Test
    void aNumberOfUnitsIsTurnedIntoExactBytes() {
        assertThat(SizeUnits.bytes(new BigDecimal("2.5"), "mb")).isEqualByComparingTo("2621440");
        assertThat(SizeUnits.bytes(new BigDecimal("0.5"), "KB")).isEqualByComparingTo("512");
        assertThat(SizeUnits.bytes(new BigDecimal("10"), "b")).isEqualByComparingTo("10");
        assertThat(SizeUnits.bytes(new BigDecimal("1.5"), "b")).as("not rounded: the caller decides").isEqualByComparingTo("1.5");
        assertThat(SizeUnits.bytes(new BigDecimal("-3"), "kb")).as("a filter may be negative").isEqualByComparingTo("-3072");
        assertThatThrownBy(() -> SizeUnits.bytes(BigDecimal.ONE, "pb")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pb");
    }

    @Test
    void thePatternMatchesTheUnitsAndOnlyThem() {
        Pattern pattern = Pattern.compile(SizeUnits.PATTERN, Pattern.CASE_INSENSITIVE);

        for (String unit : new String[] {"b", "kb", "mb", "gb", "tb", "KB", "Tb"}) {
            assertThat(pattern.matcher(unit).matches()).as(unit).isTrue();
        }
        for (String other : new String[] {"", "pb", "k", "kib", "ms", "s", "kbb"}) {
            assertThat(pattern.matcher(other).matches()).as(other).isFalse();
        }
    }
}
