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
}
