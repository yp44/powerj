package io.powerj.shell;

import java.util.ArrayList;
import java.util.List;

import org.jline.reader.CompletingParsedLine;
import org.jline.reader.EOFError;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;

import io.powerj.core.exec.Completions;
import io.powerj.core.lang.Lexer;

/**
 * Parser supplied to JLine: reports incomplete input (continuation prompt {@code >>}), splits
 * the line into words, and for completion (Tab) delimits the text to complete using {@link Completions}.
 */
final class ShellParser implements Parser {

    private final Completions completions;

    ShellParser() {
        this(null);
    }

    ShellParser(Completions completions) {
        this.completions = completions;
    }

    /**
     * Line parsed for completion: the word to complete and the computed suggestions.
     *
     * @param start start position of the replaced text (including the opening quote for a quoted path)
     */
    record CompletionLine(String line, int cursor, int start, Completions.Result result) implements CompletingParsedLine {

        @Override
        public String word() {
            return result.word();
        }

        @Override
        public int wordCursor() {
            return result.word().length();
        }

        @Override
        public int wordIndex() {
            return 0;
        }

        @Override
        public List<String> words() {
            return List.of(result.word());
        }

        /** A path containing a space is inserted between quotes, with Java escapes (FR-32b). */
        @Override
        public CharSequence escape(CharSequence candidate, boolean complete) {
            String text = candidate.toString();
            boolean quoted = start < line.length() && line.charAt(start) == '"';
            if (!quoted && text.chars().noneMatch(c -> Lexer.isBlank((char) c))) {
                return text;
            }
            var escaped = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                if (c == '\\' || c == '"' || c == '$') {
                    escaped.append('\\');
                }
                escaped.append(c);
            }
            return complete ? escaped.append('"') : escaped;
        }

        @Override
        public int rawWordCursor() {
            return cursor - start;
        }

        @Override
        public int rawWordLength() {
            return cursor - start;
        }
    }

    /** Line split into whitespace-separated words (no quotes or escaping for now). */
    record Words(String word, int wordCursor, int wordIndex, List<String> words, String line, int cursor)
            implements CompletingParsedLine {

        @Override
        public CharSequence escape(CharSequence candidate, boolean complete) {
            return candidate;
        }

        @Override
        public int rawWordCursor() {
            return wordCursor;
        }

        @Override
        public int rawWordLength() {
            return word.length();
        }
    }

    @Override
    public ParsedLine parse(String line, int cursor, ParseContext context) {
        if (context == ParseContext.ACCEPT_LINE
                && InputCompleteness.check(line) instanceof InputCompleteness.Result.Incomplete(var missing, var open)) {
            throw new EOFError(-1, cursor, "saisie incomplète", missing, open, null);
        }
        if (context == ParseContext.COMPLETE && completions != null) {
            Completions.Result result = completions.complete(line, cursor);
            return new CompletionLine(line, cursor, result.start(), result);
        }
        return split(line, cursor);
    }

    @Override
    public boolean isEscapeChar(char ch) {
        return false; // the backslash escapes nothing outside strings (FR-32b)
    }

    static Words split(String line, int cursor) {
        List<String> words = new ArrayList<>();
        int wordIndex = -1;
        int wordCursor = 0;
        int wordsBeforeCursor = 0;
        int i = 0;
        while (i < line.length()) {
            if (Lexer.isBlank(line.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < line.length() && !Lexer.isBlank(line.charAt(i))) {
                i++;
            }
            if (cursor >= start && cursor <= i) {
                wordIndex = words.size();
                wordCursor = cursor - start;
            } else if (i < cursor) {
                wordsBeforeCursor++;
            }
            words.add(line.substring(start, i));
        }
        if (wordIndex < 0) {
            // Cursor on whitespace: empty word at this position (useful for future completion).
            wordIndex = wordsBeforeCursor;
            words.add(wordIndex, "");
        }
        return new Words(words.get(wordIndex), wordCursor, wordIndex, List.copyOf(words), line, cursor);
    }
}
