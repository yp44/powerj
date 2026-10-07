package io.powerj.core.lang;

import io.powerj.core.exec.PjException;

/** Erreur de syntaxe dans la ligne saisie. */
public final class SyntaxException extends PjException {

    public SyntaxException(String message) {
        super("syntaxe : " + message);
    }
}
