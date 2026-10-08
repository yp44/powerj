package io.powerj.core.lang;

import io.powerj.core.exec.PjException;

/** Syntax error in the entered line. */
public final class SyntaxException extends PjException {

    public SyntaxException(String message) {
        super("syntaxe : " + message);
    }
}
