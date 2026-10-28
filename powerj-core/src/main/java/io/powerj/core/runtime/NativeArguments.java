package io.powerj.core.runtime;

import java.util.ArrayList;
import java.util.List;

import io.powerj.core.error.PjException;
import io.powerj.core.format.Values;
import io.powerj.core.i18n.Messages;
import io.powerj.core.nativeexec.Platform;
import io.powerj.fs.GlobExpander;
import io.powerj.fs.Globs;

/**
 * The arguments of a native program (specification FR-40): the text of each value, and the wildcards of the words
 * that were written without quotes replaced by the names of the files they match, as a Unix shell does. Nothing else
 * is expanded here: variables and {@code ~} already are (FR-62), a quoted text, a variable, a group or a block is never
 * a wildcard, and a word that matches nothing stays as written.
 * <p>
 * On Windows the program expands its own wildcards ({@code cmd /c dir *.txt}, {@code findstr}), so the words go through
 * unchanged. Whether case counts is the rule of every wildcard ({@link Globs#IGNORE_CASE_VARIABLE}).
 */
final class NativeArguments {

    private NativeArguments() {
    }

    /**
     * @param words the arguments, evaluated: their value, and the wildcard pattern of an unquoted word or of the
     *              unquoted pieces of a compound word
     * @return the arguments to give to the program
     */
    static List<String> of(List<Evaluator.Word> words, Session session) {
        List<String> arguments = new ArrayList<>(words.size());
        Boolean ignoreCase = null; // read at the first wildcard: a wrong value is no error for a line without one
        for (Evaluator.Word word : words) {
            String text = Values.text(word.value());
            if (!Platform.isWindows() && word.pattern() != null && Globs.hasWildcard(word.pattern())) {
                if (ignoreCase == null) {
                    ignoreCase = ignoreCase(session);
                }
                arguments.addAll(GlobExpander.expand(word.pattern(), text, session.currentDirectory(), ignoreCase));
            } else {
                arguments.add(text);
            }
        }
        return arguments;
    }

    private static boolean ignoreCase(Session session) {
        String value = session.environmentSnapshot().get(Globs.IGNORE_CASE_VARIABLE);
        try {
            return Globs.ignoreCase(value);
        } catch (IllegalArgumentException _) {
            throw new PjException(Messages.get("glob.ignoreCase.invalid", Globs.IGNORE_CASE_VARIABLE, value));
        }
    }
}
