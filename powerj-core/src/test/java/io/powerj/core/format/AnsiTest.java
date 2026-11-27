package io.powerj.core.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The colors of {@code $ansi} for the prompt (FR-79). */
class AnsiTest {

    private final Ansi on = new Ansi(true);
    private final Ansi off = new Ansi(false);

    @Test
    void eachColorEndsWithTheCodeThatEndsTheColorOnly() {
        assertThat(on.red("x")).isEqualTo("\033[31mx\033[39m");
        assertThat(on.green("x")).isEqualTo("\033[32mx\033[39m");
        assertThat(on.yellow("x")).isEqualTo("\033[33mx\033[39m");
        assertThat(on.blue("x")).isEqualTo("\033[34mx\033[39m");
        assertThat(on.magenta("x")).isEqualTo("\033[35mx\033[39m");
        assertThat(on.cyan("x")).isEqualTo("\033[36mx\033[39m");
        assertThat(on.white("x")).isEqualTo("\033[37mx\033[39m");
        assertThat(on.black("x")).isEqualTo("\033[30mx\033[39m");
        assertThat(on.gray("x")).isEqualTo("\033[90mx\033[39m");
        assertThat(on.color(208, "x")).isEqualTo("\033[38;5;208mx\033[39m");
    }

    @Test
    void eachStyleEndsWithTheCodeThatEndsTheStyleOnly() {
        assertThat(on.bold("x")).isEqualTo("\033[1mx\033[22m");
        assertThat(on.dim("x")).isEqualTo("\033[2mx\033[22m");
        assertThat(on.italic("x")).isEqualTo("\033[3mx\033[23m");
        assertThat(on.underline("x")).isEqualTo("\033[4mx\033[24m");
    }

    @Test
    void theCallsNest() {
        assertThat(on.bold(on.red("x"))).isEqualTo("\033[1m\033[31mx\033[39m\033[22m");
    }

    @Test
    void withoutColorsTheTextIsLeftAlone() {
        assertThat(off.red("x")).isEqualTo("x");
        assertThat(off.bold(off.color(42, "x"))).isEqualTo("x");
        assertThat(off.enabled()).isFalse();
        assertThat(on.enabled()).isTrue();
    }

    @Test
    void anyValueIsTurnedIntoText() {
        assertThat(off.green(java.nio.file.Path.of("dev"))).isEqualTo("dev");
        assertThat(off.green(null)).isEqualTo("null");
    }

    @Test
    void aColorOutsideThePaletteIsRefused() {
        assertThatThrownBy(() -> on.color(256, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> off.color(-1, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoHelpersAreEqualWhenTheyBothColorOrBothDoNot() {
        assertThat(on).isEqualTo(new Ansi(true)).hasSameHashCodeAs(new Ansi(true)).isNotEqualTo(off);
        assertThat(on).hasToString("Ansi[enabled=true]");
    }
}
