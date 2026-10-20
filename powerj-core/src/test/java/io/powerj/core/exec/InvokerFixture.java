package io.powerj.core.exec;

/** Public members for the tests of Java calls: overloads, a field, a failing getter. */
public final class InvokerFixture {

    /** Public field, read as a property. */
    public int count = 3;

    /** Getter that fails. */
    public String getBroken() {
        throw new IllegalStateException("broken");
    }

    public String join(String... words) {
        return "all:" + String.join(",", words);
    }

    public String join(String first, String... others) {
        return "first:" + first + "+" + others.length;
    }

    public String letter(char c) {
        return "char:" + c;
    }

    public String letter(Character c) {
        return "Character:" + c;
    }
}
