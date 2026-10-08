package io.powerj.core.lang;

import io.powerj.core.exec.PjException;

/** Syntax error in the entered line. */
public final class SyntaxException extends PjException {

    /** @param message already translated description, prefixed with "syntax: " in the current language */
    public SyntaxException(String message) {
        super(Messages.get("syntax.error", message));
    }
}
