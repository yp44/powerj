package io.powerj.shell;

import java.util.ArrayList;
import java.util.List;

import org.jline.reader.CompletingParsedLine;
import org.jline.reader.EOFError;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;

import io.powerj.core.lang.Lexer;

/**
 * Analyseur fourni à JLine : signale les saisies incomplètes (prompt de continuation {@code >>})
 * et découpe la ligne en mots. L'analyse du langage lui-même arrivera aux étapes suivantes.
 */
final class ShellParser implements Parser {

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
