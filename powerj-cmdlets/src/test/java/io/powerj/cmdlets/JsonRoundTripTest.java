package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.powerj.api.Row;

/**
 * What {@link JsonWriter} writes, {@link JsonParser} reads back as the same value, for values made at random from a fixed
 * seed (the same ones every run): strings with quotes, backslashes, control characters, accents, characters outside the
 * Basic Multilingual Plane and lone surrogates; numbers; nested lists and objects.
 */
class JsonRoundTripTest {

    private static final String[] PIECES = {"a", "Z", " ", "\"", "\\", "/", "\n", "\r", "\t", "\b", "\f", "\u0000",
            "\u001f", "\u007f", "é", "€", "\u2028", "\ud83d\ude00", "\ud800", "\udc00", "{", "}", "[", "]", ",", ":"};

    private static String string(Random random) {
        StringBuilder text = new StringBuilder();
        for (int i = random.nextInt(8); i > 0; i--) {
            text.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return text.toString();
    }

    private static Object value(Random random, int depth) {
        int kind = random.nextInt(depth > 3 ? 6 : 8);
        return switch (kind) {
            case 0 -> null;
            case 1 -> random.nextBoolean();
            case 2 -> random.nextLong() >> random.nextInt(64);
            case 3 -> Double.longBitsToDouble(random.nextLong() & 0x7fefffffffffffffL) * (random.nextBoolean() ? 1 : -1);
            case 4, 5 -> string(random);
            case 6 -> {
                List<Object> list = new ArrayList<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    list.add(value(random, depth + 1));
                }
                yield list;
            }
            default -> {
                Map<String, Object> members = new LinkedHashMap<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    members.put(string(random), value(random, depth + 1));
                }
                yield members;
            }
        };
    }

    /** The parser gives a {@code Row} for an object and an unmodifiable list: the same shapes as plain maps and lists. */
    private static Object plain(Object value) {
        return switch (value) {
            case Row row -> {
                Map<String, Object> members = new LinkedHashMap<>();
                row.forEach((key, member) -> members.put(key, plain(member)));
                yield members;
            }
            case List<?> list -> list.stream().map(JsonRoundTripTest::plain).toList();
            case null, default -> value;
        };
    }

    private static String write(Object value, boolean pretty) {
        StringBuilder out = new StringBuilder();
        new JsonWriter(pretty, 100).write(value, out, 0);
        return out.toString();
    }

    @Test
    void whatIsWrittenIsReadBackAsTheSameValue() {
        var random = new Random(20241122L);
        for (int i = 0; i < 3000; i++) {
            Object value = value(random, 0);
            for (boolean pretty : new boolean[] {false, true}) {
                String json = write(value, pretty);

                assertThat(plain(JsonParser.parse(json))).as(json).isEqualTo(plain(value));
            }
        }
    }

    @Test
    void aLoneSurrogateIsWrittenAsAnEscapeSoThatItSurvivesTheEncoding() {
        assertThat(write("\ud800", false)).isEqualTo("\"\\ud800\"");
        assertThat(write("a\udc00b", false)).isEqualTo("\"a\\udc00b\"");
        assertThat(write("\ud83d\ude00", false)).isEqualTo("\"\ud83d\ude00\""); // a pair is written as it is
        assertThat(write("\ude00\ud83d", false)).isEqualTo("\"\\ude00\\ud83d\""); // in the wrong order, they are two strays
        assertThat(write("\ud83d", false)).isEqualTo("\"\\ud83d\"");
    }

    @Test
    void aFloatIsWrittenWithTheDigitsItWasWrittenWith() {
        assertThat(write(0.1f, false)).isEqualTo("0.1");
        assertThat(write(3.4028235e38f, false)).isEqualTo("3.4028235E38");
        assertThat(write(-1.5f, false)).isEqualTo("-1.5");
        assertThat(write(Float.NaN, false)).isEqualTo("null");
        assertThat(write(Float.POSITIVE_INFINITY, false)).isEqualTo("null");
        assertThat(write(0.1, false)).isEqualTo("0.1");
    }
}
