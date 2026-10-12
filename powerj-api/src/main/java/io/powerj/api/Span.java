package io.powerj.api;

/**
 * Range of characters of a text, {@code start} included, {@code end} excluded, like
 * {@link java.util.regex.Matcher#start()} and {@link java.util.regex.Matcher#end()}.
 *
 * @param start index of the first character
 * @param end   index following the last character
 */
public record Span(int start, int end) {

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span: [" + start + ", " + end + ")");
        }
    }
}
