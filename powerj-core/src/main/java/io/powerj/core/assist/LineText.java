package io.powerj.core.assist;

import java.util.ArrayList;
import java.util.List;

/** Reading of the text of the line, shared by the parts of the assistance. */
final class LineText {

    private LineText() {
    }

    /** The words of a stage (blanks of any kind between them); a list of one empty word when there is none. */
    static List<String> splitWords(String text) {
        List<String> words = new ArrayList<>();
        for (String w : text.strip().split("[\\s\\u00a0\\u202f]+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        if (words.isEmpty()) {
            words.add("");
        }
        return words;
    }

    static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Is the word starting at {@code at} the first one of its stage (command name)? */
    static boolean isHead(String line, Frame frame, int at) {
        String before = line.substring(frame.stageStart(), at).strip();
        return before.isEmpty() || before.equals("^") || before.matches("\\$[\\p{L}_][\\p{L}\\p{N}_]*\\s*=");
    }

    /** Text of the expression preceding the dot (or {@code ::}) at {@code dot}. */
    static String receiverText(String line, int dot) {
        int i = dot;
        while (i > 0) {
            char c = line.charAt(i - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.' || c == '$') {
                i--;
            } else if (c == '*' && i < line.length() && line.charAt(i) == '.') {
                i--;
            } else if (c == ')' || c == ']') {
                int open = matchingBackward(line, i - 1);
                if (open < 0) {
                    break;
                }
                i = open;
            } else if (c == '"') {
                int open = line.lastIndexOf('"', i - 2);
                if (open < 0) {
                    break;
                }
                i = open;
            } else {
                break;
            }
        }
        return line.substring(i, dot);
    }

    static int matchingBackward(String line, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = line.charAt(i);
            if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
