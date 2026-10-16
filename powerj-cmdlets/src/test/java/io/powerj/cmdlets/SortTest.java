package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.powerj.api.ScriptBlock;

class SortTest {

    private final TestContext<Object> context = new TestContext<>(Path.of("."));

    {
        context.hasInput = true;
        // Simulated compilation of "$_.a.b": record accessors called one after the other.
        context.compiler = source -> block(source, current -> {
            Object value = current;
            for (String name : source.substring(3).split("\\.")) {
                try {
                    value = value.getClass().getMethod(name).invoke(value);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("no property " + name);
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

    private List<Object> sort(Sort.Params params, Object... inputs) {
        var sort = new Sort();
        sort.begin(params, context);
        for (Object input : inputs) {
            sort.process(params, input, context);
        }
        sort.end(params, context);
        return context.emitted;
    }

    private static Sort.Params params(boolean desc, boolean ignoreCase, Object... keys) {
        return new Sort.Params(new ArrayList<>(Arrays.asList(keys)), desc, ignoreCase);
    }

    private static FileEntry file(String name, long size, boolean dir) {
        return new FileEntry(name, size, Instant.EPOCH, Path.of("/tmp", name), dir, FileEntry.extensionOf(name, dir));
    }

    record Item(String name, Integer rank) {
    }

    @Test
    void sortsTheObjectsThemselves() {
        assertThat(sort(params(false, false), "pear", "apple", "fig")).containsExactly("apple", "fig", "pear");
    }

    @Test
    void comparesNumbersByValueWhateverTheirClass() {
        assertThat(sort(params(false, false), 10L, 3, 10.5, new BigDecimal("2.5"), (short) -1))
                .containsExactly((short) -1, new BigDecimal("2.5"), 3, 10L, 10.5);
        assertThat(context.errors).isEmpty();
    }

    @Test
    void descendingOrder() {
        assertThat(sort(params(true, false), 1, 3, 2)).containsExactly(3, 2, 1);
    }

    @Test
    void ignoreCase() {
        assertThat(sort(params(false, false), "b", "B", "a")).containsExactly("B", "a", "b");
        context.emitted.clear();
        assertThat(sort(params(false, true), "b", "B", "a")).containsExactly("a", "b", "B");
    }

    @Test
    void isStableInBothDirections() {
        var a = new Item("a", 1);
        var b = new Item("b", 2);
        var c = new Item("c", 1);
        var d = new Item("d", 2);
        assertThat(sort(params(false, false, "rank"), a, b, c, d)).containsExactly(a, c, b, d);
        context.emitted.clear();
        assertThat(sort(params(true, false, "rank"), a, b, c, d)).containsExactly(b, d, a, c);
    }

    @Test
    void propertyKeyOnFileEntries() {
        var big = file("big.bin", 2048, false);
        var small = file("small.txt", 10, false);
        var docs = file("docs", 0, true);
        assertThat(sort(params(true, false, "size"), small, docs, big)).containsExactly(big, small, docs);
    }

    @Test
    void propertyPath() {
        var b = file("b.txt", 1, false);
        var a = file("a.txt", 2, false);
        context.compiler = source -> {
            assertThat(source).isEqualTo("$_.path.fileName");
            return block(source, f -> ((FileEntry) f).path().getFileName());
        };
        assertThat(sort(params(false, false, "path.fileName"), b, a)).containsExactly(a, b);
    }

    @Test
    void blockKey() {
        assertThat(sort(params(false, false, block("$_.length()", s -> ((String) s).length())), "ccc", "a", "bb"))
                .containsExactly("a", "bb", "ccc");
    }

    @Test
    void severalKeysBreakTies() {
        var src = file("src", 0, true);
        var docs = file("docs", 0, true);
        var readme = file("README.md", 5, false);
        var pom = file("pom.xml", 9, false);
        assertThat(sort(params(false, false, "dir", "name"), src, readme, docs, pom))
                .containsExactly(readme, pom, docs, src);
    }

    @Test
    void nullComesFirstAscendingAndLastDescending() {
        assertThat(sort(params(false, false), "b", null, "a")).containsExactly(null, "a", "b");
        context.emitted.clear();
        assertThat(sort(params(true, false), "b", null, "a")).containsExactly("b", "a", null);
        context.emitted.clear();
        var none = new Item("none", null);
        var one = new Item("one", 1);
        assertThat(sort(params(false, false, "rank"), one, none)).containsExactly(none, one);
    }

    @Test
    void mixedTypesAreSortedByTextWithOneError() {
        assertThat(sort(params(false, false), "b", 2, "a", 1)).containsExactly(1, 2, "a", "b");
        assertThat(context.errors).hasSize(1).first().asString().contains("cannot compare");
    }

    @Test
    void aFailingKeySkipsTheObject() {
        var key = block("$_.boom", s -> {
            if (s.equals("x")) {
                throw new IllegalStateException("boom");
            }
            return s;
        });
        assertThat(sort(params(false, false, key), "b", "x", "a")).containsExactly("a", "b");
        assertThat(context.errors).hasSize(1).first().asString().contains("boom");
    }

    @Test
    void invalidKeys() {
        assertThatThrownBy(() -> sort(params(false, false, "not valid!"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid!");
        assertThatThrownBy(() -> sort(params(false, false, 3))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3");
    }

    @Test
    void emptyInputEmitsNothing() {
        assertThat(sort(params(false, false, "size"))).isEmpty();
    }
}
