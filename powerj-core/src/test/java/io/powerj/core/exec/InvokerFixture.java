package io.powerj.core.exec;

/** Public overloads for {@link JavaInvokerTest}: ties broken by the most specific overload. */
public final class InvokerFixture {

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
