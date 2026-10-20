package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.powerj.api.Bytes;
import io.powerj.api.Row;

/** {@link JsonWriter}, {@link JsonParser} and {@link JsonSplitter} (specification FR-73). */
class JsonTest {

    enum Color { RED }

    record Item(String name, @Bytes long size, List<Integer> tags) {
    }

    private static String compact(Object value) {
        StringBuilder out = new StringBuilder();
        new JsonWriter(false, 10).write(value, out, 0);
        return out.toString();
    }

    private static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        new JsonWriter(true, 10).write(value, out, 0);
        return out.toString();
    }

    // --- writer ---

    @Test
    void scalars() {
        assertThat(compact(null)).isEqualTo("null");
        assertThat(compact(true)).isEqualTo("true");
        assertThat(compact(42)).isEqualTo("42");
        assertThat(compact(10_000_000_000L)).isEqualTo("10000000000");
        assertThat(compact(new BigInteger("123456789012345678901234567890"))).isEqualTo("123456789012345678901234567890");
        assertThat(compact(new BigDecimal("1.50"))).isEqualTo("1.50");
        assertThat(compact(1.5)).isEqualTo("1.5");
        assertThat(compact(Double.NaN)).isEqualTo("null");
        assertThat(compact(Float.POSITIVE_INFINITY)).isEqualTo("null");
        assertThat(compact('x')).isEqualTo("\"x\"");
        assertThat(compact(Color.RED)).isEqualTo("\"RED\"");
        assertThat(compact(Instant.parse("2026-10-18T19:23:00Z"))).isEqualTo("\"2026-10-18T19:23:00Z\"");
        assertThat(compact(Path.of("a"))).isEqualTo("\"a\"");
        assertThat(compact(Optional.of(3))).isEqualTo("3");
        assertThat(compact(Optional.empty())).isEqualTo("null");
    }

    @Test
    void escapes() {
        assertThat(compact("a\"b\\c\nd\te\u0001é")).isEqualTo("\"a\\\"b\\\\c\\nd\\te\\u0001é\"");
    }

    @Test
    void containers() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("b", 1);
        map.put(2, List.of());
        assertThat(compact(map)).isEqualTo("{\"b\":1,\"2\":[]}");
        assertThat(compact(new int[] {1, 2})).isEqualTo("[1,2]");
        assertThat(compact(Stream.of("a", "b"))).isEqualTo("[\"a\",\"b\"]");
        assertThat(compact(Row.of(Map.of()))).isEqualTo("{}");
        assertThat(compact(new Item("a.txt", 2048, List.of(1)))).isEqualTo("{\"name\":\"a.txt\",\"size\":2048,\"tags\":[1]}");
    }

    @Test
    void indentation() {
        assertThat(pretty(new Item("a", 1, List.of(1, 2)))).isEqualTo("""
                {
                  "name": "a",
                  "size": 1,
                  "tags": [
                    1,
                    2
                  ]
                }""");
    }

    @Test
    void deeperValuesAreWrittenAsTheirText() {
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        JsonWriter writer = new JsonWriter(false, 2);
        StringBuilder out = new StringBuilder();
        writer.write(List.of(List.of(List.of(1))), out, 0);
        assertThat(out).hasToString("[[\"[1]\"]]");
        assertThat(writer.truncated()).isTrue();

        out.setLength(0);
        new JsonWriter(false, 3).write(cycle, out, 0);
        assertThat(out.toString()).startsWith("[[[\"");
    }

    // --- parser ---

    @Test
    void parsesValues() {
        assertThat(JsonParser.parse(" true ")).isEqualTo(true);
        assertThat(JsonParser.parse("null")).isNull();
        assertThat(JsonParser.parse("-0")).isEqualTo(0L);
        assertThat(JsonParser.parse("42")).isEqualTo(42L);
        assertThat(JsonParser.parse("123456789012345678901")).isEqualTo(new BigInteger("123456789012345678901"));
        assertThat(JsonParser.parse("1e3")).isEqualTo(1000.0);
        assertThat(JsonParser.parse("1.5E-2")).isEqualTo(0.015);
        assertThat(JsonParser.parse("\"a\\u00e9\\n\\\"\\/\\ud83d\\ude00\"")).isEqualTo("aé\n\"/😀");
        assertThat(JsonParser.parse("[1, [], \"x\", null]")).isEqualTo(java.util.Arrays.asList(1L, List.of(), "x", null));
    }

    @Test
    void anObjectGivesARowInTheOrderOfItsKeys() {
        Object value = JsonParser.parse("{\"b\": 1, \"a\": {\"c\": [true]}, \"b\": 2}");
        assertThat(value).isInstanceOf(Row.class);
        Row row = (Row) value;
        assertThat(row.keySet()).containsExactly("b", "a");
        assertThat(row.get("b")).isEqualTo(2L);
        assertThat(((Row) row.get("a")).get("c")).isEqualTo(List.of(true));
    }

    @Test
    void errorsGiveTheirLineAndColumn() {
        assertError("{\"a\": 1,}", 1, 9, "expected a key in quotes");
        assertError("{\n  \"a\" 1\n}", 2, 7, "expected ':' after a key");
        assertError("[1 2]", 1, 4, "expected ',' or ']' in an array");
        assertError("01", 1, 2, "a number cannot start with 0 followed by a digit");
        assertError("\"abc", 1, 1, "string not closed");
        assertError("{\"a\": 1} x", 1, 10, "unexpected text after the value: x");
        assertError("tru", 1, 1, "unexpected text: tru");
        assertError("[1,", 1, 4, "unexpected end of the JSON text");
        assertError("\"a\tb\"", 1, 3, "control character in a string (escape it, for example \\n)");
        assertError("[".repeat(JsonParser.MAX_NESTING + 1), 1, JsonParser.MAX_NESTING + 1, "nested deeper than 1000 levels");
    }

    @Test
    void emptyObjectsWhitespaceAndAllEscapes() {
        assertThat(JsonParser.parse("{ }")).isEqualTo(Row.of(Map.of()));
        assertThat(JsonParser.parse("\t\r\n 1")).isEqualTo(1L);
        assertThat(JsonParser.parse("\"\\b\\f\\r\\t\"")).isEqualTo("\b\f\r\t");
        assertThat(JsonParser.parse("-12")).isEqualTo(-12L);
        assertThat(JsonParser.parse("1e+5")).isEqualTo(100000.0);
    }

    @Test
    void truncatedObjectsAndArrays() {
        assertError("{", 1, 2, "unexpected end of the JSON text");
        assertError("{\"a\"", 1, 5, "unexpected end of the JSON text");
        assertError("{\"a\":1", 1, 7, "unexpected end of the JSON text");
        assertError("{\"a\":1 x", 1, 8, "expected ',' or '}' in an object");
        assertError("[1", 1, 3, "unexpected end of the JSON text");
    }

    @Test
    void invalidEscapesAndNumbers() {
        assertError("\"\\u12\"", 1, 2, "invalid escape: \\u must be followed by 4 hexadecimal digits");
        assertError("\"\\uZZZZ\"", 1, 2, "invalid escape: \\u must be followed by 4 hexadecimal digits");
        assertError("\"\\x\"", 1, 2, "invalid escape: \\x");
        assertError("\"abc\\", 1, 1, "string not closed");
        assertError("-", 1, 2, "invalid number: -");
        assertError("1.", 1, 3, "invalid number: 1.");
        assertError("1e", 1, 3, "invalid number: 1e");
        assertError("@", 1, 1, "unexpected text: @");
    }

    private static void assertError(String text, int line, int column, String message) {
        assertThatThrownBy(() -> JsonParser.parse(text)).isInstanceOfSatisfying(JsonParser.JsonException.class, e -> {
            assertThat(e.getMessage()).isEqualTo(message);
            assertThat(e.line()).as("line").isEqualTo(line);
            assertThat(e.column()).as("column").isEqualTo(column);
        });
    }

    @Test
    void roundTrip() {
        Object value = JsonParser.parse("{\"name\":\"a\",\"list\":[1,2.5,null,{\"x\":\"é\\n\"}],\"ok\":false}");
        assertThat(compact(value)).isEqualTo("{\"name\":\"a\",\"list\":[1,2.5,null,{\"x\":\"é\\n\"}],\"ok\":false}");
    }

    // --- splitter ---

    private static List<Object> values(List<JsonSplitter.Result> results) {
        return results.stream().map(r -> r instanceof JsonSplitter.Parsed(Object v) ? v
                : "error " + ((JsonSplitter.Failed) r).error().line() + ":" + ((JsonSplitter.Failed) r).error().column()
                        + " " + ((JsonSplitter.Failed) r).error().getMessage()).toList();
    }

    @Test
    void jsonLinesAreEmittedAsTheyArrive() {
        JsonSplitter splitter = new JsonSplitter();
        assertThat(values(splitter.feed("{\"a\": 1}"))).hasSize(1);
        assertThat(values(splitter.feed("{\"a\": 2} {\"a\": 3}"))).hasSize(2);
        assertThat(values(splitter.feed("12"))).containsExactly(12L);
        assertThat(values(splitter.feed("\"s\"true"))).containsExactly("s", true);
        assertThat(values(splitter.finish())).isEmpty();
    }

    @Test
    void aDocumentCutOverManyPieces() {
        JsonSplitter splitter = new JsonSplitter();
        List<Object> values = new ArrayList<>();
        for (String line : List.of("﻿{", "  \"a\": \"}]{[\\\"\",", "  \"b\": [1,", "    2]", "}")) {
            values.addAll(values(splitter.feed(line)));
        }
        values.addAll(values(splitter.finish()));
        assertThat(values).singleElement().isInstanceOfSatisfying(Row.class, row -> {
            assertThat(row.get("a")).isEqualTo("}]{[\"");
            assertThat(row.get("b")).isEqualTo(List.of(1L, 2L));
        });
    }

    @Test
    void readingGoesOnAfterAnInvalidValue() {
        JsonSplitter splitter = new JsonSplitter();
        List<Object> values = new ArrayList<>();
        values.addAll(values(splitter.feed("{\"a\": 1,}")));
        values.addAll(values(splitter.feed("{\"a\": 2}")));
        values.addAll(values(splitter.feed("[1, ")));
        values.addAll(values(splitter.finish()));
        assertThat(values).hasSize(3);
        assertThat(values.get(0)).isEqualTo("error 1:9 expected a key in quotes");
        assertThat(((Row) values.get(1)).get("a")).isEqualTo(2L);
        assertThat(values.get(2)).isEqualTo("error 3:1 unexpected end of the JSON text");
    }

    @Test
    void aLastNumberWithoutLineBreak() {
        JsonSplitter splitter = new JsonSplitter();
        assertThat(values(splitter.feed("7"))).containsExactly(7L); // each piece ends with a line break
        assertThat(values(splitter.finish())).isEmpty();
    }
}
