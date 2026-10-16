package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.ScriptBlock;

class KeysTest {

    private final TestContext<Object> context = new TestContext<>(Path.of("."));

    private static ScriptBlock block(String source) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return current;
            }

            @Override
            public String source() {
                return source;
            }
        };
    }

    @Test
    void aBlockIsKeptAsIs() {
        ScriptBlock block = block("$_.name.length()");
        assertThat(Keys.block(block, context, "sort.key.invalid")).isSameAs(block);
    }

    @Test
    void aPropertyIsCompiled() {
        var compiled = new ArrayList<String>();
        context.compiler = source -> {
            compiled.add(source);
            return block(source);
        };
        assertThat(Keys.blocks(List.of("size", "path.fileName"), context, "sort.key.invalid"))
                .extracting(ScriptBlock::source).containsExactly("$_.size", "$_.path.fileName");
        assertThat(compiled).containsExactly("$_.size", "$_.path.fileName");
    }

    @Test
    void noKey() {
        assertThat(Keys.blocks(null, context, "sort.key.invalid")).isEmpty();
        assertThat(Keys.blocks(List.of(), context, "sort.key.invalid")).isEmpty();
    }

    @Test
    void invalidKeys() {
        assertThatThrownBy(() -> Keys.block("a b", context, "sort.key.invalid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a b");
        assertThatThrownBy(() -> Keys.block(42, context, "sort.key.invalid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("42");
        assertThatThrownBy(() -> Keys.block(null, context, "sort.key.invalid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
    }
}
