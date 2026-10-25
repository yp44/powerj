package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.powerj.api.Row;
import io.powerj.api.ScriptBlock;

class JoinTest {

    private final TestContext<Row> context = new TestContext<>(Path.of("."));

    {
        context.hasInput = true;
        context.compiler = source -> block(source, value -> {
            String property = source.substring("$_.".length());
            return Properties.get(value, property);
        });
    }

    private static ScriptBlock block(String source, Function<Object, Object> function) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return function.apply(current);
            }

            @Override
            public String source() {
                return source;
            }
        };
    }

    private static ScriptBlock side(Object value) {
        return block("side", current -> value);
    }

    private static Row row(Object... namesAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return Row.of(values);
    }

    private List<Row> join(Join.Params params, Object... inputs) throws Exception {
        var join = new Join();
        join.begin(params, context);
        for (Object input : inputs) {
            join.process(params, input, context);
        }
        join.end(params, context);
        context.closeStates();
        return context.emitted;
    }

    private static Join.Params byKey(Object right, Object key) {
        return new Join.Params(side(right), null, key, null, null, false);
    }

    @Test
    void innerJoinMultipliesTheLeftObjectsByTheirMatches() throws Exception {
        var countries = List.of(row("code", "FR", "country", "France"), row("code", "IT", "country", "Italy"),
                row("code", "ES", "country", "Spain"));
        var zips = List.of(row("code", "FR", "zip", "75001"), row("code", "FR", "zip", "69001"),
                row("code", "IT", "zip", "00100"), row("code", "FR", "zip", "13001"));
        List<Row> rows = join(byKey(zips, "code"), countries.toArray());
        assertThat(rows).containsExactly(row("code", "FR", "country", "France", "zip", "75001"),
                row("code", "FR", "country", "France", "zip", "69001"),
                row("code", "FR", "country", "France", "zip", "13001"),
                row("code", "IT", "country", "Italy", "zip", "00100"));
        assertThat(rows.getFirst().keySet()).containsExactly("code", "country", "zip");
        assertThat(context.errors).isEmpty();
    }

    @Test
    void leftAndRightKeysMayDifferAndBeBlocks() throws Exception {
        var right = List.of(row("zipcode", "fr", "city", "Paris"));
        var params = new Join.Params(side(right), null, null, "statezip",
                block("upper", r -> ((String) ((Row) r).get("zipcode")).toUpperCase()), false);
        List<Row> rows = join(params, row("statezip", "FR", "state", "France"));
        assertThat(rows).containsExactly(row("statezip", "FR", "state", "France", "zipcode", "fr", "city", "Paris"));
    }

    @Test
    void rightKeyDefaultsToTheLeftKey() throws Exception {
        var params = new Join.Params(side(List.of(row("id", 1, "b", "x"))), null, null, "id", null, false);
        List<Row> rows = join(params, row("id", 1, "a", "y"));
        assertThat(rows.getFirst().keySet()).containsExactly("id", "a", "right_id", "b");
    }

    @Test
    void outerKeepsTheLeftObjectsWithoutMatch() throws Exception {
        var right = List.of(row("id", "1", "b", "x"));
        var params = new Join.Params(side(right), null, "id", null, null, true);
        List<Row> rows = join(params, row("id", "1", "a", "p"), row("id", "2", "a", "q"));
        assertThat(rows).containsExactly(row("id", "1", "a", "p", "b", "x"), row("id", "2", "a", "q", "b", null));
    }

    @Test
    void outerWithAnEmptyRightSideKeepsOnlyTheLeftColumns() throws Exception {
        var params = new Join.Params(side(List.of()), null, "id", null, null, true);
        assertThat(join(params, row("id", "1", "a", "p"))).containsExactly(row("id", "1", "a", "p"));
    }

    @Test
    void nullKeysNeverMatchAndIntegralNumbersAreComparedByValue() throws Exception {
        var right = List.of(row("id", null, "b", "n"), row("id", 1L, "b", "x"));
        List<Row> rows = join(byKey(right, "id"), row("id", null, "a", "m"), row("id", 1, "a", "y"));
        assertThat(rows).containsExactly(row("id", 1, "a", "y", "b", "x"));
    }

    @Test
    void collidingRightColumnsArePrefixed() throws Exception {
        var right = List.of(row("id", "1", "name", "right"));
        List<Row> rows = join(byKey(right, "id"), row("id", "1", "name", "left"));
        assertThat(rows).containsExactly(row("id", "1", "name", "left", "right_name", "right"));
    }

    @Test
    void recordsAndPlainValuesAreJoinedAndSizesKept() throws Exception {
        var file = new FileEntry("a.txt", 2048, Instant.EPOCH, Path.of("/tmp/a.txt"), false, "txt");
        var right = new Object[] {row("name", "a.txt", "owner", "me")};
        List<Row> rows = join(byKey(right, "name"), file);
        assertThat(rows.getFirst().keySet()).containsExactly("name", "size", "modified", "path", "dir", "ext", "owner");
        assertThat(rows.getFirst().get("owner")).isEqualTo("me");
        assertThat(rows.getFirst().sizeColumns()).containsExactly("size");

        context.emitted.clear();
        var params = new Join.Params(side(Stream.of("a", "b")), null, null,
                block("self", v -> v), block("self", v -> v), false);
        assertThat(join(params, "b")).containsExactly(row("value", "b", "right_value", "b"));
    }

    @Test
    void atTheHeadOfAPipelineTheLeftSideIsABlock() throws Exception {
        context.hasInput = false;
        var params = new Join.Params(side(List.of(row("id", "1", "b", "x"))),
                side(Arrays.asList(row("id", "1", "a", "y"), row("id", "2", "a", "z"))), "id", null, null, false);
        assertThat(join(params)).containsExactly(row("id", "1", "a", "y", "b", "x"));
    }

    @Test
    void keyErrorsSkipTheObject() throws Exception {
        var params = new Join.Params(side(List.of(row("id", "1"))), null, null,
                block("boom", v -> {
                    throw new IllegalStateException("boom");
                }), "id", false);
        assertThat(join(params, row("id", "1"))).isEmpty();
        assertThat(context.errors).containsExactly("boom (object skipped: {id=1})");
    }

    @Test
    void blockingErrors() {
        var join = new Join();
        assertThatThrownBy(() -> join.begin(new Join.Params(null, null, "id", null, null, false), context))
                .hasMessageStartingWith("right side expected");
        assertThatThrownBy(() -> join.begin(new Join.Params(side(List.of()), null, null, null, null, false), context))
                .hasMessageStartingWith("key expected");
        assertThatThrownBy(() -> join.begin(new Join.Params(side(List.of()), null, "id", "id", null, false), context))
                .hasMessageContaining("--key cannot be used");
        assertThatThrownBy(() -> join.begin(new Join.Params(side(List.of()), side(List.of()), "id", null, null, false),
                context)).hasMessageContaining("--left is only for the head");
        assertThatThrownBy(() -> join.begin(new Join.Params(side(List.of()), null, "bad!", null, null, false), context))
                .hasMessageContaining("bad!");
        assertThatThrownBy(() -> join.begin(new Join.Params(block("boom", v -> {
            throw new IllegalStateException("no such file");
        }), null, "id", null, null, false), context)).hasMessage("--right: no such file");
        context.hasInput = false;
        assertThatThrownBy(() -> join.begin(new Join.Params(side(List.of()), null, "id", null, null, false), context))
                .hasMessageStartingWith("left side expected");
    }
}
