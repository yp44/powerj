package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextsTest {

    @Test
    void aPropertyIsANameOrADottedPathOfNames() {
        for (String valid : new String[] {"size", "_x", "owner.name", "a.b.c", "größe", "x1", "Δ.π2"}) {
            assertThat(Texts.isPropertyPath(valid)).as(valid).isTrue();
        }
    }

    @Test
    void anythingElseIsNotAPropertyPath() {
        for (String invalid : new String[] {"", "1x", "a.", ".a", "a..b", "a b", "a-b", "a.1b", "a()", "$_.x", "a.b."}) {
            assertThat(Texts.isPropertyPath(invalid)).as("'" + invalid + "'").isFalse();
        }
    }

    @Test
    void aShortObjectIsShownAsItIs() {
        assertThat(Texts.abbreviate("abc")).isEqualTo("abc");
        assertThat(Texts.abbreviate(42)).isEqualTo("42");
        assertThat(Texts.abbreviate(null)).isEqualTo("null");
        assertThat(Texts.abbreviate("x".repeat(40))).isEqualTo("x".repeat(40));
    }

    @Test
    void aLongObjectIsCutToFortyCharactersWithAnEllipsis() {
        String cut = Texts.abbreviate("x".repeat(41));

        assertThat(cut).hasSize(40).endsWith("…").isEqualTo("x".repeat(39) + "…");
        assertThat(Texts.abbreviate("y".repeat(1_000))).hasSize(40);
    }
}
