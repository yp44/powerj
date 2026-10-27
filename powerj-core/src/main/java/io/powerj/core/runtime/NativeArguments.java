package io.powerj.core.runtime;

import java.util.ArrayList;
import java.util.List;

import io.powerj.core.error.PjException;
import io.powerj.core.format.Values;
import io.powerj.core.i18n.Messages;
import io.powerj.core.lang.Ast;
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
     * @param written the arguments as the line wrote them
     * @param values  their values, one for one ({@code ~} expanded in the words)
     * @return the arguments to give to the program
     */
    static List<String> of(List<Ast.Argument> written, List<Object> values, Session session) {
        List<String> arguments = new ArrayList<>(values.size());
        Boolean ignoreCase = null; // read at the first wildcard: a wrong value is no error for a line without one
        for (int i = 0; i < values.size(); i++) {
            String text = Values.text(values.get(i));
            if (!Platform.isWindows() && written.get(i) instanceof Ast.WordArgument && Globs.hasWildcard(text)) {
                if (ignoreCase == null) {
                    ignoreCase = ignoreCase(session);
                }
                arguments.addAll(GlobExpander.expand(text, session.currentDirectory(), ignoreCase));
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
