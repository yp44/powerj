package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.powerj.api.ScriptBlock;

class GroupTest {

    private final TestContext<Group> context = new TestContext<>(Path.of("."));

    {
        context.hasInput = true;
        context.compiler = source -> switch (source) {
            case "$_.ext" -> block(source, f -> ((FileEntry) f).ext());
            case "$_.dir" -> block(source, f -> ((FileEntry) f).dir());
            default -> throw new IllegalArgumentException(source);
        };
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

    private static FileEntry file(String name, boolean dir) {
        return new FileEntry(name, 1, Instant.EPOCH, Path.of("/tmp", name), dir, FileEntry.extensionOf(name, dir));
    }

    private List<Group> group(boolean noItems, List<Object> keys, Object... inputs) {
        var group = new GroupCmdlet();
        var params = new GroupCmdlet.Params(keys, noItems);
        group.begin(params, context);
        for (Object input : inputs) {
            group.process(params, input, context);
        }
        group.end(params, context);
        return context.emitted;
    }

    private static List<Object> keys(Object... keys) {
        return new ArrayList<>(Arrays.asList(keys));
    }

    @Test
    void groupsByAPropertyInFirstSeenOrder() {
        var b = file("B.java", false);
        var r = file("README.md", false);
        var a = file("A.java", false);
        List<Group> groups = group(false, keys("ext"), b, r, a);
        assertThat(groups).extracting(Group::key).containsExactly("java", "md");
        assertThat(groups).extracting(Group::count).containsExactly(2L, 1L);
        assertThat(groups.getFirst().items()).containsExactly(b, a);
        assertThatThrownBy(() -> groups.getFirst().items().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withoutKeyTheObjectsThemselvesAreGrouped() {
        List<Group> groups = group(false, null, "error", "ok", "error");
        assertThat(groups).extracting(Group::key).containsExactly("error", "ok");
        assertThat(groups).extracting(Group::count).containsExactly(2L, 1L);
    }

    @Test
    void severalKeysGiveAListKey() {
        List<Group> groups = group(false, keys("ext", "dir"), file("a.java", false), file("src", true),
                file("b.java", false));
        assertThat(groups).extracting(Group::key).containsExactly(List.of("java", false), List.of("", true));
        assertThat(groups.getFirst().count()).isEqualTo(2);
    }

    @Test
    void integralNumbersAreComparedByValue() {
        List<Group> groups = group(false, null, 1, 1L, (short) 1, 2);
        assertThat(groups).extracting(Group::count).containsExactly(3L, 1L);
        assertThat(groups.getFirst().key()).isEqualTo(1); // the first value seen is kept as the key
    }

    @Test
    void nullKeysFormAGroup() {
        var key = block("$_.x", v -> v.equals("none") ? null : v);
        List<Group> groups = group(false, keys(key), "a", "none", "none");
        assertThat(groups).extracting(Group::key).containsExactly("a", null);
        assertThat(groups.get(1).count()).isEqualTo(2);
    }

    @Test
    void noItemsKeepsOnlyTheCounts() {
        List<Group> groups = group(true, keys("ext"), file("a.java", false), file("b.java", false));
        assertThat(groups).singleElement().satisfies(g -> {
            assertThat(g.count()).isEqualTo(2);
            assertThat(g.items()).isEmpty();
        });
    }

    @Test
    void aFailingKeySkipsTheObject() {
        var key = block("$_.boom", v -> {
            if (v.equals("x")) {
                throw new IllegalStateException("boom");
            }
            return v;
        });
        List<Group> groups = group(false, keys(key), "a", "x", "a");
        assertThat(groups).singleElement().satisfies(g -> assertThat(g.count()).isEqualTo(2));
        assertThat(context.errors).singleElement().asString().contains("boom");
    }

    @Test
    void invalidKeysAndEmptyInput() {
        assertThatThrownBy(() -> group(false, keys("bad!"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad!");
        assertThatThrownBy(() -> group(false, keys(42))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("42");
        assertThat(group(false, keys("ext"))).isEmpty();
    }
}
