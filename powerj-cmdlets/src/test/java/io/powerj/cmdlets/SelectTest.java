package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.powerj.api.Row;
import io.powerj.api.ScriptBlock;

class SelectTest {

    private final TestContext<Row> context = new TestContext<>(Path.of("."));

    {
        context.hasInput = true;
        // Simulated compilation of "$_.a.b": record accessors, then Path.getFileName for "fileName".
        context.compiler = source -> block(source, current -> {
            Object value = current;
            for (String name : source.substring(3).split("\\.")) {
                if (value instanceof Path path && name.equals("fileName")) {
                    value = path.getFileName();
                    continue;
                }
                try {
                    value = value.getClass().getMethod(name).invoke(value);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(value.getClass().getSimpleName() + " has no property '" + name + "'");
                }
            }
            return value;
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

    private static FileEntry file(String name, long size) {
        return new FileEntry(name, size, Instant.EPOCH, Path.of("/tmp", name), false, FileEntry.extensionOf(name, false));
    }

    private List<Row> select(List<Object> columns, Object... inputs) throws Exception {
        var select = new Select();
        var params = new Select.Params(columns);
        select.begin(params, context);
        for (Object input : inputs) {
            select.process(params, input, context);
        }
        select.end(params, context);
        context.closeStates();
        return context.emitted;
    }

    private static List<Object> columns(Object... columns) {
        return new ArrayList<>(Arrays.asList(columns));
    }

    @Test
    void propertiesInTheirOrder() throws Exception {
        List<Row> rows = select(columns("size", "name"), file("a.txt", 10), file("b.txt", 20));
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().keySet()).containsExactly("size", "name");
        assertThat(rows.getFirst()).containsEntry("name", "a.txt").containsEntry("size", 10L);
        assertThat(rows.get(1)).containsEntry("name", "b.txt");
    }

    @Test
    void aPathIsNamedByItsLastSegment() throws Exception {
        List<Row> rows = select(columns("path.fileName"), file("a.txt", 10));
        assertThat(rows.getFirst()).containsOnlyKeys("fileName").containsEntry("fileName", Path.of("a.txt"));
    }

    @Test
    void renamedAndComputedColumns() throws Exception {
        var kb = block("$_.size / 1024", f -> ((FileEntry) f).size() / 1024);
        List<Row> rows = select(columns("file=name", "kb=", kb), file("a.txt", 4096));
        assertThat(rows.getFirst().keySet()).containsExactly("file", "kb");
        assertThat(rows.getFirst()).containsEntry("file", "a.txt").containsEntry("kb", 4L);
    }

    @Test
    void unnamedBlocksAreNumbered() throws Exception {
        var length = block("$_.length()", s -> ((String) s).length());
        var upper = block("$_.toUpperCase()", s -> ((String) s).toUpperCase());
        List<Row> rows = select(columns(length, upper), "ab");
        assertThat(rows.getFirst()).containsEntry("column1", 2).containsEntry("column2", "AB");
    }

    @Test
    void aMissingPropertyGivesAnEmptyCellAndOneError() throws Exception {
        List<Row> rows = select(columns("name", "nmae"), file("a.txt", 1), file("b.txt", 2));
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst()).containsEntry("nmae", null).containsEntry("name", "a.txt");
        assertThat(context.errors).singleElement().asString().startsWith("column nmae: ").contains("nmae");
    }

    @Test
    void aFailingBlockGivesAnEmptyCellAndOneError() throws Exception {
        var boom = block("$_.boom", s -> {
            throw new IllegalStateException("boom");
        });
        List<Row> rows = select(columns("x=", boom), "a", "b");
        assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("x", null));
        assertThat(context.errors).containsExactly("column x: boom");
    }

    @Test
    void sizeColumnsComeFromBytesComponents() throws Exception {
        var kb = block("$_.size", f -> ((FileEntry) f).size());
        Row row = select(columns("name", "size", "bytes=size", "kb=", kb), file("a.txt", 1)).getFirst();
        assertThat(row.sizeColumns()).containsExactlyInAnyOrder("size", "bytes");
    }

    @Test
    void atTheHeadOfAPipelineItGivesOneRowFromBlocksOnly() throws Exception {
        context.hasInput = false;
        var content = block("(cat file.txt)", current -> "text of " + current);
        var other = block("(cat another.txt)", current -> "other");
        List<Row> rows = select(columns("content=", content, "other=", other));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().keySet()).containsExactly("content", "other");
        assertThat(rows.getFirst()).containsEntry("content", "text of null").containsEntry("other", "other");
        assertThat(rows.getFirst().sizeColumns()).isEmpty();
        assertThat(context.errors).isEmpty();
    }

    @Test
    void atTheHeadOfAPipelineAPropertyIsAnEmptyCellAndOneError() throws Exception {
        context.hasInput = false;
        context.compiler = source -> block(source, current -> {
            throw new IllegalStateException("no current object");
        });
        List<Row> rows = select(columns("name"));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("name", null);
        assertThat(context.errors).containsExactly("column name: no current object");
    }

    @Test
    void invalidColumns() {
        assertThatThrownBy(() -> select(columns())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column expected");
        assertThatThrownBy(() -> select(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> select(columns("a", "name", "a=size"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column a is given twice");
        assertThatThrownBy(() -> select(columns("my-col=name"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("my-col");
        assertThatThrownBy(() -> select(columns("kb="))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block expected after kb=");
        assertThatThrownBy(() -> select(columns("bad!"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad!");
        assertThatThrownBy(() -> select(columns(42))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("42");
    }

    @Test
    void rowIsAnImmutableOrderedMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("b", 1);
        values.put("a", null);
        Row row = Row.of(values, Set.of("b"));
        values.put("c", 3); // the row keeps its own copy
        assertThat(row.keySet()).containsExactly("b", "a");
        assertThat(row.get("a")).isNull();
        assertThat(row.get("missing")).isNull();
        assertThat(row.containsKey("a")).isTrue();
        assertThat(row.sizeColumns()).containsExactly("b");
        Map<String, Object> same = new LinkedHashMap<>();
        same.put("b", 1);
        same.put("a", null);
        assertThat(row).isEqualTo(same);
        assertThat(row.toString()).isEqualTo("{b=1, a=null}");
        assertThatThrownBy(() -> row.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> row.entrySet().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Row.of(Map.of("x", 1)).sizeColumns()).isEmpty();
    }

    @Test
    void columnsReadFromTheArgumentsForCompletion() {
        assertThat(new Select().columns(" a b.c d=e f={ x { y } } { z } --on-error stop"))
                .containsExactly("a", "c", "d", "f", "column1");
        assertThat(new Select().columns(" name kb={ $_.size")).containsExactly("name", "kb"); // still being typed
        assertThat(new Select().columns("")).isEmpty();
    }
}
