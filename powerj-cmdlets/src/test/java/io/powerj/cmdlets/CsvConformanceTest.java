package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * RFC 4180 as {@link CsvReader} and {@link CsvWriter} apply it: one section of the RFC per test, then what is written
 * is read back as the same records, for records made at random from a fixed seed.
 */
class CsvConformanceTest {

    /** The records of the text, given as the pieces the reader receives (each one ends with a line break). */
    private static List<List<String>> read(char delimiter, String... pieces) {
        var reader = new CsvReader(delimiter);
        List<List<String>> records = new ArrayList<>();
        for (String piece : pieces) {
            reader.feed(piece).forEach(record -> records.add(record.fields()));
        }
        return records;
    }

    private static List<List<String>> read(String... pieces) {
        return read(',', pieces);
    }

    @Test
    void eachRecordIsLocatedOnItsOwnLine() { // RFC 4180, 2.1
        assertThat(read("aaa,bbb,ccc", "zzz,yyy,xxx")).containsExactly(List.of("aaa", "bbb", "ccc"),
                List.of("zzz", "yyy", "xxx"));
    }

    @Test
    void aCarriageReturnAndALineFeedEndARecordAndSoDoesAloneOne() { // RFC 4180, 2.1 (CRLF), and the leniency
        assertThat(read("a,b\r\nc,d")).containsExactly(List.of("a", "b"), List.of("c", "d"));
        assertThat(read("a,b\rc,d")).containsExactly(List.of("a", "b"), List.of("c", "d"));
        assertThat(read("a,b\nc,d")).containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void theLastRecordNeedsNoLineBreakAndAnEmptyLineIsNoRecord() { // RFC 4180, 2.2 and a leniency
        assertThat(read("aaa,bbb")).containsExactly(List.of("aaa", "bbb"));
        assertThat(read("a", "", "", "b")).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void fieldsMayBeEnclosedInDoubleQuotes() { // RFC 4180, 2.5
        assertThat(read("\"aaa\",\"bbb\",\"ccc\"")).containsExactly(List.of("aaa", "bbb", "ccc"));
    }

    @Test
    void aQuotedFieldMayHoldTheDelimiterAndLineBreaks() { // RFC 4180, 2.6
        assertThat(read("\"a,b\",c")).containsExactly(List.of("a,b", "c"));
        assertThat(read("\"aaa\",\"b", "bb\",\"ccc\"", "zzz,yyy,xxx"))
                .containsExactly(List.of("aaa", "b\nbb", "ccc"), List.of("zzz", "yyy", "xxx"));
        assertThat(read("\"a\r\nb\",c")).containsExactly(List.of("a\r\nb", "c"));
    }

    @Test
    void aQuoteInsideAQuotedFieldIsEscapedByAnotherQuote() { // RFC 4180, 2.7
        assertThat(read("\"aaa\",\"b\"\"bb\",\"ccc\"")).containsExactly(List.of("aaa", "b\"bb", "ccc"));
        assertThat(read("\"\"\"\"")).containsExactly(List.of("\""));
    }

    @Test
    void spacesBelongToTheField() { // RFC 4180, 2.4
        assertThat(read(" a , b ,c")).containsExactly(List.of(" a ", " b ", "c"));
    }

    @Test
    void emptyFieldsAreFields() {
        assertThat(read("a,,c")).containsExactly(List.of("a", "", "c"));
        assertThat(read(",")).containsExactly(List.of("", ""));
        assertThat(read("a,")).containsExactly(List.of("a", ""));
    }

    @Test
    void aQuotedEmptyFieldIsARecordEvenAlone() { // what lets a column of empty values survive
        assertThat(read("\"\"")).containsExactly(List.of(""));
        assertThat(read("\"\"", "", "x")).containsExactly(List.of(""), List.of("x"));
    }

    @Test
    void anotherDelimiter() {
        assertThat(read(';', "a;b,c;\"d;e\"")).containsExactly(List.of("a", "b,c", "d;e"));
        assertThat(read('\t', "a\tb c\t")).containsExactly(List.of("a", "b c", ""));
    }

    @Test
    void aByteOrderMarkAtTheStartIsNotPartOfTheFirstField() {
        assertThat(read("\uFEFFname,size", "a,1")).containsExactly(List.of("name", "size"), List.of("a", "1"));
        assertThat(read("name", "\uFEFFx")).containsExactly(List.of("name"), List.of("\uFEFFx")); // only at the start
    }

    @Test
    void aTextThatEndsInsideQuotesIsReportedAsUnclosed() {
        var reader = new CsvReader(',');
        assertThat(reader.feed("a,\"b")).isEmpty();
        assertThat(reader.unclosed()).isTrue();
        assertThat(reader.recordLine()).isEqualTo(1);
    }

    // --- what is written is read back ---

    private static final String[] PIECES = {"", "a", "Z", " ", ",", ";", "\"", "\n", "\r", "\r\n", "\t", "é", "€",
            "\ud83d\ude00", "x y", "''", "\\"};

    private static String field(Random random) {
        StringBuilder text = new StringBuilder();
        for (int i = random.nextInt(4); i > 0; i--) {
            text.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return text.toString();
    }

    @Test
    void whatIsWrittenIsReadBackAsTheSameRecords() {
        var random = new Random(4180L);
        for (char delimiter : new char[] {',', ';', '\t'}) {
            var writer = new CsvWriter(delimiter);
            for (int i = 0; i < 3000; i++) {
                List<String> fields = new ArrayList<>();
                for (int n = 1 + random.nextInt(4); n > 0; n--) {
                    fields.add(field(random));
                }
                String line = writer.line(fields);

                assertThat(read(delimiter, line)).as(line.replace("\n", "\\n").replace("\r", "\\r"))
                        .containsExactly(fields);
            }
        }
    }

    @Test
    void aRecordOfOneEmptyFieldIsWrittenQuotedSoThatItIsNotTakenForAnEmptyLine() {
        assertThat(new CsvWriter(',').line(List.of(""))).isEqualTo("\"\"");
        assertThat(new CsvWriter(',').line(java.util.Collections.singletonList(null))).isEqualTo("\"\"");
        assertThat(new CsvWriter(',').line(List.of("", ""))).isEqualTo(",");
        assertThat(new CsvWriter(',').line(List.of("a"))).isEqualTo("a");
    }
}
