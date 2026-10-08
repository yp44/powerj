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
 * Analyseur fourni à JLine : signale les saisies incomplètes (prompt de continuation {@code >>}), découpe
 * la ligne en mots, et pour la complétion (Tab) délimite le texte à compléter grâce à {@link Completions}.
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
     * Ligne analysée pour la complétion : le mot à compléter et les propositions calculées.
     *
     * @param start position du début du texte remplacé (guillemet ouvrant compris pour un chemin quoté)
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

        /** Un chemin contenant un espace est inséré entre guillemets, avec les échappements Java (FR-32b). */
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

    /** Ligne découpée en mots séparés par des blancs (sans guillemets ni échappement pour l'instant). */
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
        return false; // l'antislash n'échappe rien hors des chaînes (FR-32b)
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
            // Curseur sur un blanc : mot vide à cette position (utile à la future complétion).
            wordIndex = wordsBeforeCursor;
            words.add(wordIndex, "");
        }
        return new Words(words.get(wordIndex), wordCursor, wordIndex, List.copyOf(words), line, cursor);
    }
}
