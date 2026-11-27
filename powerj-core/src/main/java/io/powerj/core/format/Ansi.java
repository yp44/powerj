package io.powerj.core.format;

import java.util.Objects;

/**
 * Colors and styles for a text written by the user, mostly the prompt (FR-79): the value of {@code $ansi}, so that
 * {@code $prompt = { "PJ " + $ansi.green($pwd) + "> " }} needs no escape code typed by hand. Each method puts the text
 * between the code that sets a color or a style and the one that ends that color or that style only, so that the calls
 * nest ({@code $ansi.bold($ansi.red("x"))}). When colors are off (no terminal, {@code NO_COLOR}), every method gives the
 * text back as it is.
 */
public final class Ansi {

    private static final String ESC = "\033[";
    private static final String FOREGROUND_OFF = "39";
    /** Bold and dim are ended by the same code. */
    private static final String INTENSITY_OFF = "22";

    private final boolean enabled;

    /** @param enabled {@code false} when the terminal shows no colors: the texts are left alone */
    public Ansi(boolean enabled) {
        this.enabled = enabled;
    }

    /** {@code true} if the texts are given colors. */
    public boolean enabled() {
        return enabled;
    }

    public String black(Object text) {
        return wrap("30", FOREGROUND_OFF, text);
    }

    public String red(Object text) {
        return wrap("31", FOREGROUND_OFF, text);
    }

    public String green(Object text) {
        return wrap("32", FOREGROUND_OFF, text);
    }

    public String yellow(Object text) {
        return wrap("33", FOREGROUND_OFF, text);
    }

    public String blue(Object text) {
        return wrap("34", FOREGROUND_OFF, text);
    }

    public String magenta(Object text) {
        return wrap("35", FOREGROUND_OFF, text);
    }

    public String cyan(Object text) {
        return wrap("36", FOREGROUND_OFF, text);
    }

    public String white(Object text) {
        return wrap("37", FOREGROUND_OFF, text);
    }

    /** Bright black: what terminals show as gray. */
    public String gray(Object text) {
        return wrap("90", FOREGROUND_OFF, text);
    }

    /**
     * One of the 256 colors of the terminal palette: 0 to 15 are the colors above and their bright forms, 16 to 231 a
     * 6×6×6 cube, 232 to 255 shades of gray.
     *
     * @throws IllegalArgumentException if {@code color} is not between 0 and 255
     */
    public String color(int color, Object text) {
        if (color < 0 || color > 255) {
            throw new IllegalArgumentException("a color of the palette is between 0 and 255: " + color);
        }
        return wrap("38;5;" + color, FOREGROUND_OFF, text);
    }

    public String bold(Object text) {
        return wrap("1", INTENSITY_OFF, text);
    }

    public String dim(Object text) {
        return wrap("2", INTENSITY_OFF, text);
    }

    public String italic(Object text) {
        return wrap("3", "23", text);
    }

    public String underline(Object text) {
        return wrap("4", "24", text);
    }

    private String wrap(String on, String off, Object text) {
        String value = String.valueOf(text);
        return enabled ? ESC + on + "m" + value + ESC + off + "m" : value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Ansi ansi && ansi.enabled == enabled;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(enabled);
    }

    @Override
    public String toString() {
        return "Ansi[enabled=" + enabled + "]";
    }
}
