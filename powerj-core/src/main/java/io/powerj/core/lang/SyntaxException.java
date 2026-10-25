package io.powerj.core.lang;

/**
 * Syntax error in the entered line. The language depends on nothing else of the core: whoever runs the line turns this
 * into the error the user sees.
 */
public final class SyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message already translated description, prefixed with "syntax: " in the current language */
    public SyntaxException(String message) {
        super(Messages.get("syntax.error", message));
    }
}
