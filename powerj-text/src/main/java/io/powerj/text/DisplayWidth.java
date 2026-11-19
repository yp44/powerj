package io.powerj.text;

/**
 * How many columns of a terminal a text takes. {@code String.length()} counts UTF-16 units, which is wrong for what a
 * table of names shows: a Japanese or Korean character, a fullwidth letter and an emoji take two columns (and an emoji is
 * two units), a combining mark takes none (audit v2, U-04).
 * <p>
 * East Asian Wide and Fullwidth ranges and the emoji count 2, combining marks, format characters (the zero width joiner)
 * and controls 0, the rest 1. The characters whose width depends on the terminal (East Asian Ambiguous) count 1.
 */
public final class DisplayWidth {

    private static final String ELLIPSIS = "…";

    private DisplayWidth() {
    }

    /** The columns of {@code text}. */
    public static int of(String text) {
        int columns = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            columns += of(codePoint);
            i += Character.charCount(codePoint);
        }
        return columns;
    }

    /** The columns of one character: 0, 1 or 2. */
    public static int of(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.CONTROL,
                    Character.COMBINING_SPACING_MARK -> {
                return 0;
            }
            default -> {
                // not a mark
            }
        }
        return wide(codePoint) ? 2 : 1;
    }

    private static boolean wide(int cp) {
        return cp >= 0x1100 && cp <= 0x115F // Hangul Jamo
                || cp >= 0x2E80 && cp <= 0x303E // CJK radicals, symbols and punctuation
                || cp >= 0x3041 && cp <= 0x33FF // Hiragana, Katakana, Bopomofo, CJK compatibility
                || cp >= 0x3400 && cp <= 0x4DBF // CJK extension A
                || cp >= 0x4E00 && cp <= 0x9FFF // CJK unified ideographs
                || cp >= 0xA000 && cp <= 0xA4CF // Yi
                || cp >= 0xAC00 && cp <= 0xD7A3 // Hangul syllables
                || cp >= 0xF900 && cp <= 0xFAFF // CJK compatibility ideographs
                || cp >= 0xFE30 && cp <= 0xFE6F // CJK compatibility forms, small forms
                || cp >= 0xFF00 && cp <= 0xFF60 // fullwidth forms
                || cp >= 0xFFE0 && cp <= 0xFFE6 // fullwidth signs
                || cp >= 0x20000 && cp <= 0x3FFFD // CJK extensions B and later
                || Character.isEmojiPresentation(cp);
    }

    /**
     * {@code text} if it fits in {@code width} columns, otherwise its longest beginning that leaves a column for the
     * ellipsis, followed by it. A character is never cut: not a surrogate pair, and not a wide character that would
     * overflow.
     */
    public static String truncate(String text, int width) {
        if (of(text) <= width) {
            return text;
        }
        int room = Math.max(0, width - 1);
        int columns = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int next = columns + of(codePoint);
            if (next > room) {
                break;
            }
            columns = next;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    /** {@code text} followed by the spaces that make it {@code width} columns; as it is if it is wider. */
    public static String padEnd(String text, int width) {
        return text + " ".repeat(Math.max(0, width - of(text)));
    }
}
