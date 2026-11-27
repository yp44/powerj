package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpanAndScriptBlockTest {

    @Test
    void aSpanIsAHalfOpenRange() {
        assertThat(new Span(2, 5)).extracting(Span::start, Span::end).containsExactly(2, 5);
        assertThat(new Span(3, 3)).isEqualTo(new Span(3, 3));
    }

    @Test
    void aSpanRejectsNegativeStartAndReversedBounds() {
        assertThatThrownBy(() -> new Span(-1, 2)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[-1, 2)");
        assertThatThrownBy(() -> new Span(4, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ScriptBlock returning(Object value) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return value;
            }

            @Override
            public String source() {
                return "block";
            }
        };
    }

    @Test
    void testReadsABooleanResult() {
        assertThat(returning(true).test("x")).isTrue();
        assertThat(returning(Boolean.FALSE).test("x")).isFalse();
    }

    @Test
    void aBlockOfOneArgumentRefusesTwoOrMore() {
        ScriptBlock echo = new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return current;
            }

            @Override
            public String source() {
                return "$_";
            }
        };
        assertThat(echo.invoke()).isNull();
        assertThat(echo.invoke((Object) null)).isNull();
        Object[] array = {"x", "y"};
        assertThat(echo.invoke(array)).isSameAs(array); // one argument: the array itself
        assertThatThrownBy(() -> echo.invoke("a", "b")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
        assertThatThrownBy(() -> echo.invoke("a", "b", "c")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3");
    }

    @Test
    void testRefusesAnythingButABoolean() {
        assertThatThrownBy(() -> returning("yes").test("x")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("String yes");
        assertThatThrownBy(() -> returning(null).test("x")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null");
        assertThatThrownBy(() -> returning(1).test("x")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Integer 1");
    }
}
