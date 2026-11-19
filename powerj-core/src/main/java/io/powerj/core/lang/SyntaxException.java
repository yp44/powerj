package io.powerj.core.lang;

/**
 * Syntax error in the entered line. The language depends on nothing else of the core: whoever runs the line turns this
 * into the error the user sees.
 */
public final class SyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int offset;

    /** @param message already translated description, prefixed with "syntax: " in the current language */
    public SyntaxException(String message) {
        this(message, -1);
    }

    /** @param offset index in the line (UTF-16 units) of what is wrong, or -1 if it is not known */
    public SyntaxException(String message, int offset) {
        super(Messages.get("syntax.error", message));
        this.offset = offset;
    }

    private SyntaxException(String fullMessage, int offset, boolean prefixed) {
        super(fullMessage);
        this.offset = offset;
    }

    /**
     * The same error without a place: a block is parsed alone, so what it says is relative to the block, not to the line
     * that the user typed, and a caret under the line would point at the wrong character.
     */
    SyntaxException withoutOffset() {
        return offset < 0 ? this : new SyntaxException(getMessage(), -1, true);
    }

    /** The number of a "(position N)" in a message: the column of the error, counted in characters. */
    private static final java.util.regex.Pattern POSITION = java.util.regex.Pattern.compile("(position )(\\d+)");

    /**
     * The same error, for a text that was parsed alone but starts at {@code start} of {@code line} (the pipeline of a group
     * {@code ( … )}): the place, and the "(position N)" of the message, are moved into the line (review of #175).
     */
    SyntaxException within(String line, int start) {
        if (offset < 0) {
            return this;
        }
        int columns = line.codePointCount(0, Math.min(start, line.length()));
        var matcher = POSITION.matcher(getMessage());
        var message = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(message, matcher.group(1) + (Integer.parseInt(matcher.group(2)) + columns));
        }
        matcher.appendTail(message);
        return new SyntaxException(message.toString(), offset + start, true);
    }

    /** Index in the line (UTF-16 units) of what is wrong, or -1. */
    public int offset() {
        return offset;
    }
}
