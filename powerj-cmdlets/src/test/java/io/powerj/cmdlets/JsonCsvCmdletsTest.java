package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.Row;
import io.powerj.api.ScriptBlock;

/** The cmdlets {@code to-json}, {@code from-json}, {@code to-csv} and {@code from-csv} (specification FR-73). */
class JsonCsvCmdletsTest {

    private static <P extends Record, O> List<O> run(Cmdlet<P, Object, O> cmdlet, P params, TestContext<O> context,
                                                     Object... inputs) {
        context.hasInput = true;
        try {
            cmdlet.begin(params, context);
            for (Object input : inputs) {
                cmdlet.process(params, input, context);
            }
            cmdlet.end(params, context);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return context.emitted;
    }

    private static <P extends Record, O> List<O> run(Cmdlet<P, Object, O> cmdlet, P params, Object... inputs) {
        return run(cmdlet, params, new TestContext<>(Path.of(".")), inputs);
    }

    private static FileEntry entry(String name, long size) {
        return new FileEntry(name, size, Instant.parse("2026-10-18T10:00:00Z"), Path.of("/w/" + name), false, "txt");
    }

    private static Row row(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return Row.of(values);
    }

    // --- to-json ---

    @Test
    void toJsonIsAlwaysAnArray() {
        assertThat(run(new ToJson(), new ToJson.Params(false, false, null), row("name", "a", "size", 1L)))
                .containsExactly("""
                        [
                          {
                            "name": "a",
                            "size": 1
                          }
                        ]""");
        assertThat(run(new ToJson(), new ToJson.Params(true, false, null), row("a", 1), row("a", 2)))
                .containsExactly("[{\"a\":1},{\"a\":2}]");
        assertThat(run(new ToJson(), new ToJson.Params(false, false, null))).containsExactly("[]");
    }

    @Test
    void toJsonLinesEmitsOneObjectPerLine() {
        assertThat(run(new ToJson(), new ToJson.Params(false, true, null), entry("a.txt", 2048), "s"))
                .containsExactly("{\"name\":\"a.txt\",\"size\":2048,\"modified\":\"2026-10-18T10:00:00Z\","
                        + "\"path\":\"" + Path.of("/w/a.txt") .toString().replace("\\", "\\\\") + "\",\"dir\":false,\"ext\":\"txt\"}", "\"s\"");
    }

    @Test
    void toJsonDepth() {
        TestContext<String> context = new TestContext<>(Path.of("."));
        run(new ToJson(), new ToJson.Params(true, false, 1), context, List.of(List.of(1)), List.of(List.of(2)));
        assertThat(context.emitted).containsExactly("[[\"[1]\"],[\"[2]\"]]");
        assertThat(context.errors).containsExactly(
                "values nested deeper than 1 levels are written as their text (raise --depth)");
        assertThatThrownBy(() -> run(new ToJson(), new ToJson.Params(false, false, 0)))
                .hasMessage("--depth expects a depth of at least 1, got 0");
    }

    // --- from-json ---

    @Test
    void fromJsonUnrollsATopLevelArrayAndReadsJsonLines() {
        List<Object> out = run(new FromJson(), new FromJson.Params(null), "[", "  {\"a\": 1},", "  {\"a\": 2}", "]",
                "{\"a\": 3}", "4");
        assertThat(out).hasSize(4);
        assertThat(out.subList(0, 3)).extracting(o -> ((Row) o).get("a")).containsExactly(1L, 2L, 3L);
        assertThat(out.get(3)).isEqualTo(4L);
    }

    @Test
    void fromJsonReportsInvalidJsonAndGoesOn() {
        TestContext<Object> context = new TestContext<>(Path.of("."));
        run(new FromJson(), new FromJson.Params(null), context, "{\"a\": 1,}", "\"ok\"", "data.json");
        assertThat(context.emitted).containsExactly("ok");
        assertThat(context.errors).containsExactly(
                "invalid JSON at line 1, column 9: expected a key in quotes",
                "invalid JSON at line 3, column 1: unexpected text: data.json");
    }

    @Test
    void fromJsonNeedsTextOrAProperty() {
        TestContext<Object> context = new TestContext<>(Path.of("."));
        run(new FromJson(), new FromJson.Params(null), context, 42, "1");
        assertThat(context.emitted).containsExactly(1L);
        assertThat(context.errors).singleElement().asString()
                .startsWith("not a JSON text: 42 (use --property");

        TestContext<Object> noInput = new TestContext<>(Path.of("."));
        assertThatThrownBy(() -> new FromJson().begin(new FromJson.Params(null), noInput))
                .hasMessageStartingWith("JSON text expected");
    }

    @Test
    void fromJsonProperty() {
        TestContext<Object> context = new TestContext<>(Path.of("."));
        context.compiler = source -> {
            assertThat(source).isEqualTo("$_.content");
            return block(o -> o instanceof CatContent c ? c.content() : o instanceof Row r ? r.get("content") : null);
        };
        run(new FromJson(), new FromJson.Params("content"), context, new CatContent(9, "{\"a\": 1}\n"),
                row("content", null), row("content", 5));
        assertThat(context.emitted).singleElement().extracting(o -> ((Row) o).get("a")).isEqualTo(1L);
        assertThat(context.errors).containsExactly("property content is not a text: 5");
    }

    private static ScriptBlock block(java.util.function.Function<Object, Object> f) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return f.apply(current);
            }

            @Override
            public String source() {
                return "$_.content";
            }
        };
    }

    // --- to-csv ---

    @Test
    void toCsvStreamsTheHeaderThenOneLinePerObject() {
        List<String> out = run(new ToCsv(), new ToCsv.Params(null, false), row("name", "a,b", "size", 1L),
                row("size", 2L, "other", "x"), "scalar");
        assertThat(out).containsExactly("name,size", "\"a,b\",1", ",2", ",");
        assertThat(run(new ToCsv(), new ToCsv.Params(";", true), entry("a.txt", 10)))
                .containsExactly("a.txt;10;2026-10-18T10:00:00Z;" + Path.of("/w/a.txt") + ";false;txt");
        assertThat(run(new ToCsv(), new ToCsv.Params(null, false), "x", 1)).containsExactly("value", "x", "1");
    }

    // --- from-csv ---

    @Test
    void fromCsvGivesRowsOfStrings() {
        TestContext<Row> context = new TestContext<>(Path.of("."));
        run(new FromCsv(), new FromCsv.Params(";", false, null), context, "﻿name;size;name;", "a.txt;007;x;",
                "", "\"b;c\";1.5", "d;1;2;3;4");
        assertThat(context.emitted).hasSize(3);
        assertThat(context.emitted.get(0)).containsExactly(Map.entry("name", "a.txt"), Map.entry("size", "007"),
                Map.entry("name_2", "x"), Map.entry("c4", ""));
        assertThat(context.emitted.get(1).get("name")).isEqualTo("b;c");
        assertThat(context.emitted.get(1).get("name_2")).isNull();
        assertThat(context.errors).containsExactly("line 5: 5 fields for 4 columns (extra fields ignored)");
    }

    @Test
    void fromCsvWithoutHeaderAndUnclosedQuote() {
        TestContext<Row> context = new TestContext<>(Path.of("."));
        run(new FromCsv(), new FromCsv.Params(null, true, null), context, "a,b\n1,2\n", "\"open");
        assertThat(context.emitted).extracting(r -> r.get("c1")).containsExactly("a", "1");
        assertThat(context.errors).containsExactly("line 4: quoted field not closed");
    }

    @Test
    void csvRoundTrip() {
        List<String> lines = run(new ToCsv(), new ToCsv.Params(null, false),
                row("name", "two\nlines, \"quoted\"", "size", 10L), row("name", " b ", "size", null));
        List<String> pieces = new ArrayList<>();
        lines.forEach(line -> pieces.addAll(List.of(line.split("\n", -1))));
        List<Row> rows = run(new FromCsv(), new FromCsv.Params(null, false, null), pieces.toArray());
        assertThat(rows).extracting(r -> r.get("name")).containsExactly("two\nlines, \"quoted\"", " b ");
        assertThat(rows).extracting(r -> r.get("size")).containsExactly("10", "");
    }

    @Test
    void jsonRoundTrip() {
        List<String> json = run(new ToJson(), new ToJson.Params(false, false, null), entry("a.txt", 10),
                entry("b.txt", 20));
        List<Object> rows = run(new FromJson(), new FromJson.Params(null), json.getFirst());
        assertThat(rows).extracting(o -> ((Row) o).get("name")).containsExactly("a.txt", "b.txt");
        assertThat(rows).extracting(o -> ((Row) o).get("size")).containsExactly(10L, 20L);
    }
}
