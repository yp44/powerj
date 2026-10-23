package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import org.junit.jupiter.api.Test;

class FilterParserTest {

    private final FilterParser utc = new FilterParser(ZoneId.of("UTC"));

    private Parsed text(String expression) {
        return utc.parse(expression, ColumnKind.TEXT, ColumnKind.TEXT);
    }

    private Parsed number(String expression) {
        return utc.parse(expression, ColumnKind.NUMBER, ColumnKind.NUMBER);
    }

    private Parsed size(String expression) {
        return utc.parse(expression, ColumnKind.SIZE, ColumnKind.SIZE);
    }

    private Parsed date(String expression) {
        return utc.parse(expression, ColumnKind.DATE, ColumnKind.DATE);
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    // --- nothing, and text ---

    @Test
    void anEmptyExpressionFiltersNothing() {
        for (String expression : new String[] {null, "", "   "}) {
            Parsed parsed = text(expression);
            assertThat(parsed.isValid()).isTrue();
            assertThat(parsed.isActive()).isFalse();
            assertThat(parsed.keeps("anything")).isTrue();
            assertThat(parsed.keeps(null)).isTrue();
        }
    }

    @Test
    void aTextIsLookedForWithoutRegardToCase() {
        Parsed parsed = text("ABC");
        assertThat(parsed.keeps("xabcx")).isTrue();
        assertThat(parsed.keeps("AbC")).isTrue();
        assertThat(parsed.keeps("ab")).isFalse();
        assertThat(parsed.keeps(null)).isFalse();
        assertThat(text("  abc  ").keeps("abc")).isTrue();
    }

    @Test
    void theTextShownAndTheValueAreBothLookedAt() {
        // 14520 bytes is shown 14.2 KB.
        assertThat(size("kb").keeps(14_520L)).isTrue();
        assertThat(size("14.2").keeps(14_520L)).isTrue();
        assertThat(size("14520").keeps(14_520L)).isTrue();
        assertThat(size("mb").keeps(14_520L)).isFalse();
        assertThat(text("1").keeps(100)).isTrue();
    }

    @Test
    void equalsMeansTheWholeCell() {
        Parsed parsed = text("=Parser.java");
        assertThat(parsed.keeps("parser.java")).isTrue();
        assertThat(parsed.keeps("Parser.java.bak")).isFalse();
        assertThat(text("= a b ").keeps("a b")).isTrue();
    }

    @Test
    void equalsAloneMeansAnEmptyCell() {
        Parsed parsed = text("=");
        assertThat(parsed.keeps(null)).isTrue();
        assertThat(parsed.keeps("")).isTrue();
        assertThat(parsed.keeps("x")).isFalse();
    }

    @Test
    void notMeansDoesNotContain() {
        Parsed parsed = text("!test");
        assertThat(parsed.negated()).isTrue();
        assertThat(parsed.keeps("Parser.java")).isTrue();
        assertThat(parsed.keeps("ParserTest.java")).isFalse();
        assertThat(parsed.keeps(null)).isTrue();
        assertThat(parsed.keeps(5)).isTrue();
    }

    @Test
    void anOperatorAloneFiltersNothingSoTypingItDoesNotEmptyTheTable() {
        for (String expression : new String[] {"!", "! ", ">", ">=", "<", "<=", ".."}) {
            assertThat(number(expression).isActive()).as(expression).isFalse();
            assertThat(number(expression).isValid()).as(expression).isTrue();
        }
        assertThat(text("!").isActive()).isFalse();
        assertThat(date(">").keeps(Instant.now())).isTrue();
        assertThat(size("..").keeps(1L)).isTrue();
    }

    // --- regular expressions ---

    @Test
    void aRegexIsBetweenSlashes() {
        Parsed parsed = text("/^a.*z$/");
        assertThat(parsed.keeps("abcz")).isTrue();
        assertThat(parsed.keeps("ABCZ")).isTrue();
        assertThat(parsed.keeps("xabcz")).isFalse();
        assertThat(parsed.keeps(null)).isFalse();
        assertThat(text("/\\d+/").keeps("file12")).isTrue();
    }

    @Test
    void aRegexLooksAtTheTextShownAndTheValue() {
        assertThat(size("/kb$/").keeps(14_520L)).isTrue();
        assertThat(size("/^14520$/").keeps(14_520L)).isTrue();
        assertThat(size("/mb$/").keeps(14_520L)).isFalse();
    }

    @Test
    void anInvalidRegexIsReported() {
        Parsed parsed = text("/(/");
        assertThat(parsed.isValid()).isFalse();
        assertThat(parsed.isActive()).isFalse();
        assertThat(parsed.error()).startsWith("invalid regular expression: ");
    }

    @Test
    void anEmptyRegexMatchesEverythingAndASingleSlashIsText() {
        assertThat(text("//").keeps("x")).isTrue();
        assertThat(text("/").keeps("a/b")).isTrue();
        assertThat(text("/").keeps("ab")).isFalse();
    }

    // --- numbers ---

    @Test
    void operatorsOnNumbers() {
        assertThat(number(">5").keeps(6)).isTrue();
        assertThat(number(">5").keeps(5)).isFalse();
        assertThat(number(">=5").keeps(5)).isTrue();
        assertThat(number("<5").keeps(4)).isTrue();
        assertThat(number("<5").keeps(5)).isFalse();
        assertThat(number("<=5").keeps(5)).isTrue();
        assertThat(number("<=5").keeps(6)).isFalse();
        assertThat(number("=5").keeps(5L)).isTrue();
        assertThat(number("=5").keeps(5.0)).isTrue();
        assertThat(number("=5").keeps(6)).isFalse();
        assertThat(number("> 5").keeps(6)).isTrue();
        assertThat(number(">-3").keeps(-2)).isTrue();
        assertThat(number(">2.5").keeps(2.6)).isTrue();
        assertThat(number(">2,5").keeps(2.6)).isTrue();
        assertThat(number(">1e3").keeps(2000)).isTrue();
    }

    @Test
    void rangesOnNumbers() {
        assertThat(number("5..10").keeps(5)).isTrue();
        assertThat(number("5..10").keeps(10)).isTrue();
        assertThat(number("5..10").keeps(11)).isFalse();
        assertThat(number("5..10").keeps(4)).isFalse();
        assertThat(number("5..").keeps(1_000_000)).isTrue();
        assertThat(number("5..").keeps(4)).isFalse();
        assertThat(number("..10").keeps(-50)).isTrue();
        assertThat(number("..10").keeps(11)).isFalse();
        assertThat(number(" 5 .. 10 ").keeps(7)).isTrue();
    }

    @Test
    void anyKindOfNumberIsCompared() {
        Parsed parsed = number(">5");
        assertThat(parsed.keeps((byte) 6)).isTrue();
        assertThat(parsed.keeps((short) 6)).isTrue();
        assertThat(parsed.keeps(6L)).isTrue();
        assertThat(parsed.keeps(5.5f)).isTrue();
        assertThat(parsed.keeps(5.5)).isTrue();
        assertThat(parsed.keeps(new BigDecimal("5.1"))).isTrue();
        assertThat(parsed.keeps(BigInteger.TEN.pow(30))).isTrue();
        assertThat(parsed.keeps(new java.util.concurrent.atomic.AtomicInteger(9))).isTrue();
    }

    @Test
    void whatIsNotANumberIsNotKept() {
        Parsed parsed = number(">5");
        assertThat(parsed.keeps(null)).isFalse();
        assertThat(parsed.keeps("10")).isFalse();
        assertThat(parsed.keeps(Double.NaN)).isFalse();
        assertThat(parsed.keeps(Float.POSITIVE_INFINITY)).isFalse();
        assertThat(parsed.keeps(Double.NEGATIVE_INFINITY)).isFalse();
    }

    @Test
    void aNumberWithoutOperatorIsText() {
        assertThat(number("10").keeps(100)).isTrue();
        assertThat(number("10").keeps(25)).isFalse();
    }

    @Test
    void aBadNumberIsReported() {
        Parsed parsed = number(">abc");
        assertThat(parsed.isValid()).isFalse();
        assertThat(parsed.error()).isEqualTo("not a number: abc");
        assertThat(number("1..x").error()).isEqualTo("not a number: x");
        assertThat(number("x..2").error()).isEqualTo("not a number: x");
    }

    @Test
    void equalsOnANumberFallsBackToText() {
        assertThat(number("=abc").isValid()).isTrue();
        assertThat(number("=abc").keeps("ABC")).isTrue();
    }

    @Test
    void operatorsAreOnlyForNumbersAndDates() {
        assertThat(text(">5").keeps("a>5b")).isTrue();
        assertThat(text(">5").keeps(9)).isFalse();
        assertThat(text("1..5").keeps("x1..5")).isTrue();
    }

    // --- sizes ---

    @Test
    void sizesTakeUnits() {
        assertThat(size(">10kb").keeps(10_240L)).isFalse();
        assertThat(size(">10kb").keeps(10_241L)).isTrue();
        assertThat(size(">=10kb").keeps(10_240L)).isTrue();
        assertThat(size("<1.5mb").keeps(1_572_863L)).isTrue();
        assertThat(size("<1.5mb").keeps(1_572_864L)).isFalse();
        assertThat(size(">=2gb").keeps(2L << 30)).isTrue();
        assertThat(size(">1tb").keeps(1L << 40)).isFalse();
        assertThat(size(">=1TB").keeps(1L << 40)).isTrue();
        assertThat(size(">1,5kb").keeps(1_537L)).isTrue();
        assertThat(size(">10 KB").keeps(10_241L)).isTrue();
        assertThat(size(">100").keeps(101L)).isTrue();
        assertThat(size(">100b").keeps(100L)).isFalse();
        assertThat(size("=1kb").keeps(1_024L)).isTrue();
        assertThat(size("=1kb").keeps(1_025L)).isFalse();
    }

    @Test
    void sizeRanges() {
        assertThat(size("1kb..1mb").keeps(1_024L)).isTrue();
        assertThat(size("1kb..1mb").keeps(1_048_576L)).isTrue();
        assertThat(size("1kb..1mb").keeps(1_048_577L)).isFalse();
        assertThat(size("1kb..1mb").keeps(100L)).isFalse();
        assertThat(size("..1kb").keeps(100L)).isTrue();
    }

    @Test
    void aBadSizeIsReported() {
        assertThat(size(">10xb").error()).isEqualTo("not a size (4096, 10kb, 1.5mb, 2gb…): 10xb");
        assertThat(size(">kb").isValid()).isFalse();
    }

    // --- dates ---

    @Test
    void aDateNamesAWholePeriod() {
        Instant moment = at("2026-10-05T18:12:30Z");
        assertThat(date("=2026").keeps(moment)).isTrue();
        assertThat(date("=2025").keeps(moment)).isFalse();
        assertThat(date("=2026-10").keeps(moment)).isTrue();
        assertThat(date("=2026-09").keeps(moment)).isFalse();
        assertThat(date("=2026-10-05").keeps(moment)).isTrue();
        assertThat(date("=2026-10-06").keeps(moment)).isFalse();
        assertThat(date("=2026-10-05 18:12").keeps(moment)).isTrue();
        assertThat(date("=2026-10-05T18:12").keeps(moment)).isTrue();
        assertThat(date("=2026-10-05 18:13").keeps(moment)).isFalse();
        assertThat(date("=2026-10-05 18:12:30").keeps(moment)).isTrue();
        assertThat(date("=2026-10-05 18:12:31").keeps(moment)).isFalse();
    }

    @Test
    void afterAndBeforeAPeriod() {
        Instant moment = at("2026-10-05T18:12:30Z");
        assertThat(date(">2026-10").keeps(moment)).isFalse();             // after October
        assertThat(date(">2026-10").keeps(at("2026-11-01T00:00:00Z"))).isTrue();
        assertThat(date(">=2026-10").keeps(moment)).isTrue();
        assertThat(date(">=2026-10-05").keeps(at("2026-10-05T00:00:00Z"))).isTrue();
        assertThat(date(">2026-10-04").keeps(moment)).isTrue();
        assertThat(date("<2026-10-05").keeps(moment)).isFalse();          // before the day starts
        assertThat(date("<2026-10-05").keeps(at("2026-10-04T23:59:59Z"))).isTrue();
        assertThat(date("<=2026-10-05").keeps(moment)).isTrue();          // up to the end of the day
        assertThat(date("<=2026-10-05").keeps(at("2026-10-06T00:00:00Z"))).isFalse();
        assertThat(date("<2027").keeps(moment)).isTrue();
    }

    @Test
    void rangesOfDatesIncludeTheLastPeriod() {
        assertThat(date("2026-10-01..2026-10-05").keeps(at("2026-10-05T23:59:59Z"))).isTrue();
        assertThat(date("2026-10-01..2026-10-05").keeps(at("2026-10-06T00:00:00Z"))).isFalse();
        assertThat(date("2026-10-01..2026-10-05").keeps(at("2026-09-30T23:59:59Z"))).isFalse();
        assertThat(date("2026..").keeps(at("2030-01-01T00:00:00Z"))).isTrue();
        assertThat(date("2026..").keeps(at("2025-12-31T23:59:59Z"))).isFalse();
        assertThat(date("..2025").keeps(at("2025-12-31T23:59:59Z"))).isTrue();
        assertThat(date("..2025").keeps(at("2026-01-01T00:00:00Z"))).isFalse();
    }

    @Test
    void datesAreTypedInTheZoneOfTheTable() {
        var paris = new FilterParser(ZoneId.of("Europe/Paris"));
        Parsed day = paris.parse("=2026-10-05", ColumnKind.DATE, ColumnKind.DATE); // summer time: UTC+2
        assertThat(day.keeps(at("2026-10-04T22:30:00Z"))).isTrue();
        assertThat(day.keeps(at("2026-10-04T21:30:00Z"))).isFalse();
        assertThat(day.keeps(at("2026-10-05T21:59:00Z"))).isTrue();
        assertThat(day.keeps(at("2026-10-05T22:00:00Z"))).isFalse();
    }

    @Test
    void everyKindOfDateIsCompared() {
        Parsed parsed = date("=2026-10-05");
        assertThat(parsed.keeps(Date.from(at("2026-10-05T10:00:00Z")))).isTrue();
        assertThat(parsed.keeps(at("2026-10-05T10:00:00Z").atZone(ZoneId.of("UTC")))).isTrue();
        assertThat(parsed.keeps(at("2026-10-05T10:00:00Z").atOffset(java.time.ZoneOffset.UTC))).isTrue();
        assertThat(parsed.keeps(LocalDateTime.of(2026, 10, 5, 10, 0))).isTrue();
        assertThat(parsed.keeps(LocalDate.of(2026, 10, 5))).isTrue();
        assertThat(parsed.keeps(LocalDate.of(2026, 10, 6))).isFalse();
        assertThat(parsed.keeps("2026-10-05")).isFalse();
        assertThat(parsed.keeps(null)).isFalse();
        assertThat(parsed.keeps(java.time.Year.of(2026))).isFalse();
    }

    @Test
    void aBadDateIsReported() {
        assertThat(date(">abc").error())
                .isEqualTo("not a date (2026, 2026-10, 2026-10-05, 2026-10-05 18:12): abc");
        assertThat(date(">2026-13").isValid()).isFalse();
        assertThat(date(">2026-02-30").isValid()).isFalse();
        assertThat(date(">2026-10-05 25:00").isValid()).isFalse();
        assertThat(date("2026-01..nope").isValid()).isFalse();
    }

    @Test
    void equalsOnADateFallsBackToText() {
        assertThat(date("=hello").isValid()).isTrue();
        assertThat(date("=").keeps(null)).isTrue();
    }

    @Test
    void aDateWithoutOperatorIsText() {
        // The text of the cell is a local date and time ("2026-10-05 18:12").
        assertThat(date("2026").keeps(at("2026-10-05T18:12:30Z"))).isTrue();
        assertThat(date("1999").keeps(at("2026-10-05T18:12:30Z"))).isFalse();
    }
}
