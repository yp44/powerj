package io.powerj.text;

import java.lang.reflect.Method;

/**
 * The name of the property that a getter stands for, as for a JavaBean: {@code getName()} is {@code name}, {@code isDirectory()}
 * (a {@code boolean} or {@code Boolean}) is {@code directory}, and a name that starts with two capitals is kept, so that
 * {@code getURL()} is {@code URL}. The core lists the properties of an object and finds a getter by this name, and the grid
 * makes a column of it: the rule is written once, here, so that a column that the grid shows is a property that the engine reads
 * (the case of a name matters, #130).
 */
public final class BeanProperties {

    private BeanProperties() {
    }

    /**
     * The name of the property of {@code getter}, or {@code null} if it is not a getter: it takes no argument, returns something,
     * is called {@code getX} or {@code isX} (a {@code boolean} or {@code Boolean}), and its name goes on with a capital letter
     * ({@code getx()} and {@code get_x()} are not getters). Whether the method is static, or declared by {@code Object}, is for the
     * caller to say.
     */
    public static String nameOf(Method getter) {
        if (getter.getParameterCount() != 0 || getter.getReturnType() == void.class) {
            return null;
        }
        String name = getter.getName();
        String stem;
        if (name.startsWith("get") && name.length() > 3) {
            stem = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2
                && (getter.getReturnType() == boolean.class || getter.getReturnType() == Boolean.class)) {
            stem = name.substring(2);
        } else {
            return null;
        }
        if (!Character.isUpperCase(stem.charAt(0))) {
            return null;
        }
        return stem.length() > 1 && Character.isUpperCase(stem.charAt(1)) ? stem
                : Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
    }
}
