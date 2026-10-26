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
import org.junit.jupiter.api.Timeout;

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
    void aTextConditionLooksAtTheTextShownAndNotAtTheValue() {
        // The search box reads every column as text: a size is looked for as it is shown.
        assertThat(utc.parse("kb", ColumnKind.TEXT, ColumnKind.SIZE).keeps(14_520L)).isTrue();
        assertThat(utc.parse("14.2", ColumnKind.TEXT, ColumnKind.SIZE).keeps(14_520L)).isTrue();
        assertThat(utc.parse("14520", ColumnKind.TEXT, ColumnKind.SIZE).keeps(14_520L)).isFalse();
        assertThat(utc.parse("mb", ColumnKind.TEXT, ColumnKind.SIZE).keeps(14_520L)).isFalse();
        assertThat(text("1").keeps(100)).isTrue(); // a text column that holds a number: its text is "100"
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
    void aRegexLooksAtTheTextShownEvenOnATypedColumn() {
        assertThat(size("/kb$/").keeps(14_520L)).isTrue();
        assertThat(size("/^14\\.2/").keeps(14_520L)).isTrue();
        assertThat(size("/^14520$/").keeps(14_520L)).isFalse();
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

    // --- a regex that never ends (review C-18) ---

    /**
     * The JDK now memoizes the textbook cases ({@code (a+)+$} answers at once), but eleven nested lazy loops still try every
     * way to place eleven commas among forty: about 10^9 steps, which is minutes, for a cell of eighty characters.
     */
    private static final String CATASTROPHIC = "/(.*?,){11}P/";
    private static final String CATASTROPHIC_CELL = "1,".repeat(40);

    @Test
    @Timeout(20)
    void aRegexThatBacktracksWithoutEndGivesUpOnTheCellInsteadOfFreezing() {
        Parsed parsed = text(CATASTROPHIC);

        assertThat(parsed.keeps(CATASTROPHIC_CELL)).isFalse();
        assertThat(parsed.abandoned()).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    void afterAFewCellsTheRegexIsNotTriedOnTheOthersAndTheyAreCounted() {
        Parsed parsed = text(CATASTROPHIC);

        for (int i = 0; i < 5_000; i++) {
            assertThat(parsed.keeps(CATASTROPHIC_CELL)).isFalse();
        }

        assertThat(parsed.abandoned()).isEqualTo(5_000);
    }

    @Test
    void aRegexOnOrdinaryCellsGivesUpOnNothing() {
        Parsed parsed = text("/^a+b$/");

        assertThat(parsed.keeps("aaab")).isTrue();
        assertThat(parsed.keeps("aaac")).isFalse();
        assertThat(parsed.abandoned()).isZero();
    }

    @Test
    void aLongCellIsReadWholeWhenTheRegexIsSimple() {
        Parsed parsed = text("/needle/");

        assertThat(parsed.keeps("x".repeat(2_000_000) + "needle")).isTrue();
        assertThat(parsed.keeps("x".repeat(2_000_000))).isFalse();
        assertThat(parsed.abandoned()).isZero();
    }

    @Test
    void theOtherFiltersNeverGiveUp() {
        assertThat(text("abc").abandoned()).isZero();
        assertThat(text("=abc").abandoned()).isZero();
        assertThat(number(">3").abandoned()).isZero();
        assertThat(Parsed.NONE.abandoned()).isZero();
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
    void aNumberWithoutOperatorMeansEqual() {
        assertThat(number("10").keeps(10)).isTrue();
        assertThat(number("10").keeps(10.0)).isTrue();
        assertThat(number("10").keeps(10L)).isTrue();
        assertThat(number("10").keeps(100)).isFalse();
        assertThat(number("10").keeps(25)).isFalse();
        assertThat(number("10").keeps("10")).isFalse();
        assertThat(number("10").keeps(null)).isFalse();
        assertThat(number("0.1").keeps(0.1)).isTrue();
        assertThat(number("=0.1").keeps(0.1f)).isTrue();
    }

    @Test
    void aNumberIsNeverLookedForInTheDigitsOfOthers() {
        // 228 bytes: not 2280, 12288 or 3280, which only contain the digits.
        assertThat(size("228").keeps(228L)).isTrue();
        assertThat(size("228").keeps(2_280L)).isFalse();
        assertThat(size("228").keeps(12_288L)).isFalse();
        assertThat(size("228").keeps(3_280L)).isFalse();
        assertThat(number("228").keeps(1_228)).isFalse();
    }

    @Test
    void aBadNumberIsReported() {
        Parsed parsed = number(">abc");
        assertThat(parsed.isValid()).isFalse();
        assertThat(parsed.error()).isEqualTo("not a number: abc");
        assertThat(number("1..x").error()).isEqualTo("not a number: x");
        assertThat(number("x..2").error()).isEqualTo("not a number: x");
        assertThat(number("abc").error()).isEqualTo("not a number: abc");
        assertThat(number("=abc").error()).isEqualTo("not a number: abc");
        assertThat(number("!abc").error()).isEqualTo("not a number: abc");
    }

    @Test
    void equalsAloneFindsTheEmptyCellsOfATypedColumnToo() {
        assertThat(number("=").keeps(null)).isTrue();
        assertThat(number("=").keeps(5)).isFalse();
        assertThat(date("=").keeps(null)).isTrue();
        assertThat(size("=").keeps(0L)).isFalse();
    }

    @Test
    void notOnATypedColumnMeansNotEqual() {
        assertThat(number("!5").keeps(5)).isFalse();
        assertThat(number("!5").keeps(6)).isTrue();
        assertThat(number("!5").keeps(null)).isTrue();
        assertThat(number("!5").negated()).isTrue();
        assertThat(size("!1kb").keeps(1_024L)).isFalse();
        assertThat(size("!1kb").keeps(5_000L)).isTrue();
        assertThat(date("!2026").keeps(at("2025-06-01T00:00:00Z"))).isTrue();
        assertThat(date("!2026").keeps(at("2026-06-01T00:00:00Z"))).isFalse();
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
    }

    @Test
    void aSizeTypedAsItIsShownFindsWhatIsShownLikeThat() {
        // 2.2 KB is what is shown for 2.15 to 2.25 KB, that is 2201.6 to 2304 bytes.
        assertThat(size("=2.2kb").keeps(2_202L)).isTrue();
        assertThat(size("2.2kb").keeps(2_252L)).isTrue();
        assertThat(size("2.2kb").keeps(2_303L)).isTrue();
        assertThat(size("2.2kb").keeps(2_201L)).isFalse();
        assertThat(size("2.2kb").keeps(2_304L)).isFalse();
        assertThat(size("2.2 KB").keeps(2_252L)).isTrue();
        assertThat(size("2,2kb").keeps(2_252L)).isTrue();
        assertThat(size("12kb").keeps(12_288L)).isTrue();
        assertThat(size("1kb").keeps(1_025L)).isTrue();   // shown 1.0 KB
        assertThat(size("1kb").keeps(1_100L)).isFalse();  // shown 1.1 KB
        assertThat(size("!2.2kb").keeps(2_252L)).isFalse();
        assertThat(size("!2.2kb").keeps(9_000L)).isTrue();
    }

    @Test
    void moreDecimalsOrBytesAreExact() {
        assertThat(size("2.25kb").keeps(2_304L)).isTrue();
        assertThat(size("2.25kb").keeps(2_303L)).isFalse();
        assertThat(size("228b").keeps(228L)).isTrue();
        assertThat(size("228b").keeps(229L)).isFalse();
        assertThat(size("=228").keeps(228L)).isTrue();
    }

    @Test
    void whatIsShownIsAlwaysFoundByTypingIt() {
        for (long bytes = 1_024; bytes < 3_000_000_000L; bytes += bytes < 5_000_000 ? 997 : 7_654_321) {
            String shown = CellFormatter.text(bytes, ColumnKind.SIZE);            // e.g. "14.2 KB"
            String typed = shown.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
            assertThat(size(typed).keeps(bytes)).as(bytes + " shown " + shown).isTrue();
        }
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
        assertThat(size("kb").error()).isEqualTo("not a size (4096, 10kb, 1.5mb, 2gb…): kb");
        assertThat(size("=abc").isValid()).isFalse();
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
    void aBadEqualDateIsReported() {
        assertThat(date("=hello").error()).isEqualTo("not a date (2026, 2026-10, 2026-10-05, 2026-10-05 18:12): hello");
        assertThat(date("hello").isValid()).isFalse();
        assertThat(date("2026-10-05 18").isValid()).isFalse();
    }

    @Test
    void aDateWithoutOperatorNamesAPeriod() {
        assertThat(date("2026").keeps(at("2026-10-05T18:12:30Z"))).isTrue();
        assertThat(date("2026").keeps(at("2025-10-05T18:12:30Z"))).isFalse();
        assertThat(date("2026-10-05").keeps(at("2026-10-05T01:00:00Z"))).isTrue();
        assertThat(date("2026-10-05").keeps(at("2026-10-06T01:00:00Z"))).isFalse();
        assertThat(date("2026-10-05").keeps(null)).isFalse();
    }

}
