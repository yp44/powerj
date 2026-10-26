package io.powerj.fx.model;

/** What the Esc key does in the window (the view only has to carry it out). */
public enum EscapeAction {

    /** A list of choices is open: Esc closes it, and nothing else happens. */
    LEAVE_TO_LIST,
    /** A text field holds text: Esc empties it and the filters are applied. */
    CLEAR_FIELD,
    /** Anything else: the window closes. */
    CLOSE_WINDOW;

    /**
     * @param listShowing  the focus is on a list of choices that is open
     * @param fieldHasText the focus is on a text field that is not empty
     */
    public static EscapeAction of(boolean listShowing, boolean fieldHasText) {
        if (listShowing) {
            return LEAVE_TO_LIST;
        }
        return fieldHasText ? CLEAR_FIELD : CLOSE_WINDOW;
    }
}
