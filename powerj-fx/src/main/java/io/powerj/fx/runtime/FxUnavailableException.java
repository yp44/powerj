package io.powerj.fx.runtime;

/** The graphical toolkit cannot be used (no display, missing native library, start-up timeout). */
public final class FxUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
