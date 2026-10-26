package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.Row;

/** The choices {@link JsonParser} and {@link JsonSplitter} make where RFC 8259 leaves them open (review C-25). */
class JsonConformanceTest {

    @Test
    void aNumberThatIsTooBigForADoubleIsAnErrorNotInfinity() {
        for (String text : List.of("1e999", "-1e999", "123123e100000", "1.5E+9999", "[0.4e0066999999999999999]")) {
            assertThatThrownBy(() -> JsonParser.parse(text)).as(text).isInstanceOf(JsonParser.JsonException.class)
                    .hasMessageContaining("out of range");
        }
    }

    @Test
    void aNumberThatIsTooSmallIsZeroAndTheBiggestDoublesAreFine() {
        assertThat(JsonParser.parse("1e-999")).isEqualTo(0.0);
        assertThat(JsonParser.parse("123e-10000000")).isEqualTo(0.0);
        assertThat(JsonParser.parse("1.7976931348623157e308")).isEqualTo(Double.MAX_VALUE);
        assertThat(JsonParser.parse("-1e308")).isEqualTo(-1e308);
        assertThat(JsonParser.parse("4.9e-324")).isEqualTo(Double.MIN_VALUE);
    }

    @Test
    void theErrorOfAnOutOfRangeNumberSaysWhereIt() {
        assertThatThrownBy(() -> JsonParser.parse("[1,\n 1e999]")).isInstanceOfSatisfying(
                JsonParser.JsonException.class, e -> {
                    assertThat(e.line()).isEqualTo(2);
                    assertThat(e.column()).isEqualTo(2);
                });
    }

    @Test
    void anIntegerBeyondLongIsABigInteger() {
        assertThat(JsonParser.parse("100000000000000000000")).isEqualTo(new java.math.BigInteger("100000000000000000000"));
        assertThat(JsonParser.parse("-9223372036854775809")).isEqualTo(new java.math.BigInteger("-9223372036854775809"));
        assertThat(JsonParser.parse("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void whenAKeyIsRepeatedTheLastValueWins() {
        assertThat(((Row) JsonParser.parse("{\"a\":1,\"b\":0,\"a\":2}"))).containsEntry("a", 2L).hasSize(2);
        assertThat(((Row) JsonParser.parse("{\"a\":1,\"b\":0,\"a\":2}")).keySet()).containsExactly("a", "b");
    }

    @Test
    void aLoneSurrogateEscapeIsReadAsTheCharacter() {
        assertThat(JsonParser.parse("\"\\ud800\"")).isEqualTo("\ud800");
        assertThat(JsonParser.parse("\"\\udc00\\ud800\"")).isEqualTo("\udc00\ud800");
        assertThat(JsonParser.parse("\"\\ud83d\\ude00\"")).isEqualTo("\ud83d\ude00");
    }

    @Test
    void theNestingLimitIsTheLastLevelThatIsFine() {
        String ok = "[".repeat(JsonParser.MAX_NESTING) + "]".repeat(JsonParser.MAX_NESTING);
        String tooDeep = "[".repeat(JsonParser.MAX_NESTING + 1) + "]".repeat(JsonParser.MAX_NESTING + 1);

        assertThat(JsonParser.parse(ok)).isInstanceOf(List.class);
        assertThatThrownBy(() -> JsonParser.parse(tooDeep)).isInstanceOf(JsonParser.JsonException.class)
                .hasMessageContaining("deep");
    }

    // --- the splitter ---

    private static List<JsonSplitter.Result> all(JsonSplitter splitter, String... pieces) {
        List<JsonSplitter.Result> results = new ArrayList<>();
        for (String piece : pieces) {
            results.addAll(splitter.feed(piece));
        }
        results.addAll(splitter.finish());
        return results;
    }

    @Test
    void aByteOrderMarkAtTheStartOfTheInputIsSkipped() {
        var results = all(new JsonSplitter(), "\uFEFF{\"a\":1}", "{\"a\":2}");

        assertThat(results).hasSize(2).allSatisfy(result -> assertThat(result).isInstanceOf(JsonSplitter.Parsed.class));
    }

    @Test
    void aValueLongerThanTheLimitIsReportedOnceAndReadingStops() {
        var splitter = new JsonSplitter(20);

        var results = all(splitter, "{\"a\": \"", "0123456789", "0123456789\"}", "{\"b\": 1}", "[2]");

        assertThat(results).singleElement().isInstanceOfSatisfying(JsonSplitter.Failed.class, failed -> {
            assertThat(failed.error().getMessage()).contains("more than 20 characters");
            assertThat(failed.error().line()).isEqualTo(1);
            assertThat(failed.error().column()).isEqualTo(1);
        });
    }

    @Test
    void aValueJustWithinTheLimitIsRead() {
        var splitter = new JsonSplitter(20);

        var results = all(splitter, "{\"a\": 123456789}", "[1]");

        assertThat(results).hasSize(2).allSatisfy(r -> assertThat(r).isInstanceOf(JsonSplitter.Parsed.class));
    }

    @Test
    void theLimitIsTheLongestValueThatIsRead() {
        String exactly = "[" + "1".repeat(18) + "]"; // 20 characters
        String oneMore = "[" + "1".repeat(19) + "]"; // 21 characters

        assertThat(all(new JsonSplitter(20), exactly)).singleElement().isInstanceOf(JsonSplitter.Parsed.class);
        assertThat(all(new JsonSplitter(20), oneMore)).singleElement().isInstanceOf(JsonSplitter.Failed.class);
    }

    @Test
    void manySmallValuesAreNeverTooMuch() {
        var splitter = new JsonSplitter(20);
        List<JsonSplitter.Result> results = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            results.addAll(splitter.feed("{\"i\": " + i + "}"));
        }

        assertThat(results).hasSize(1000).allSatisfy(r -> assertThat(r).isInstanceOf(JsonSplitter.Parsed.class));
    }
}
