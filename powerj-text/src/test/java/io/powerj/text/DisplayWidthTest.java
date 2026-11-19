package io.powerj.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** How many terminal columns a text takes (audit v2, U-04, #174). */
class DisplayWidthTest {

    @Test
    void plainTextTakesOneColumnPerCharacter() {
        assertThat(DisplayWidth.of("")).isZero();
        assertThat(DisplayWidth.of("abc")).isEqualTo(3);
        assertThat(DisplayWidth.of("é")).isEqualTo(1);
    }

    @Test
    void eastAsianWideAndFullwidthCharactersTakeTwo() {
        assertThat(DisplayWidth.of("日本語")).isEqualTo(6);
        assertThat(DisplayWidth.of("한글")).isEqualTo(4);
        assertThat(DisplayWidth.of("ＡＢ")).isEqualTo(4); // fullwidth Latin
    }

    @Test
    void anEmojiTakesTwoAndIsOneCharacterOfTwoUtf16Units() {
        assertThat(DisplayWidth.of("😀")).isEqualTo(2);
        assertThat("😀".length()).isEqualTo(2);
    }

    @Test
    void aCombiningMarkAndAJoinerTakeNothing() {
        assertThat(DisplayWidth.of("é")).isEqualTo(1); // e + combining acute
        assertThat(DisplayWidth.of("a‍b")).isEqualTo(2); // zero width joiner
    }

    @Test
    void truncationKeepsWholeCharactersAndEndsWithAnEllipsisThatFits() {
        assertThat(DisplayWidth.truncate("abc", 3)).isEqualTo("abc");
        assertThat(DisplayWidth.truncate("abcdef", 4)).isEqualTo("abc…");
        // 日本語 is 6 columns: in 4 there is room for 日 (2) and the ellipsis (1), not for 本 (would make 5)
        assertThat(DisplayWidth.truncate("日本語", 4)).isEqualTo("日…");
        assertThat(DisplayWidth.truncate("😀😀😀", 4)).isEqualTo("😀…");
        assertThat(DisplayWidth.truncate("😀😀😀", 1)).isEqualTo("…");
        assertThat(DisplayWidth.truncate("abc", 0)).isEqualTo("…");
    }

    @Test
    void aTruncatedTextNeverEndsInHalfASurrogatePair() {
        for (int width = 0; width < 8; width++) {
            String cut = DisplayWidth.truncate("😀😀😀😀", width);
            assertThat(cut.codePoints().allMatch(cp -> cp != 0xFFFD && !Character.isSurrogate((char) cp)
                    || Character.isSupplementaryCodePoint(cp))).as(cut).isTrue();
            assertThat(DisplayWidth.of(cut)).isLessThanOrEqualTo(Math.max(1, width));
        }
    }
}
