package io.powerj.cmdlets;

import java.util.regex.Pattern;

/** Two small rules about text that several cmdlets share. */
final class Texts {

    /** A property as {@code $_.name} reaches it: names made of letters, digits and {@code _}, separated by dots. */
    private static final Pattern PROPERTY_PATH = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*(\\.[\\p{L}_][\\p{L}\\p{N}_]*)*");

    /** Longest object shown in a message; longer ones are cut. */
    static final int ABBREVIATED_LENGTH = 40;

    private Texts() {
    }

    /** Whether {@code text} is a property name or a path of them ({@code size}, {@code owner.name}). */
    static boolean isPropertyPath(String text) {
        return PROPERTY_PATH.matcher(text).matches();
    }

    /** The text of an object for a message: at most {@value #ABBREVIATED_LENGTH} characters, ending in an ellipsis if cut. */
    static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() > ABBREVIATED_LENGTH ? text.substring(0, ABBREVIATED_LENGTH - 1) + "…" : text;
    }
}
