package io.powerj.fx.model;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Locale;

/** Reads a property by reflection, as the shell does for {@code $_.name}: record component, then getter. */
public final class Reflect {

    private Reflect() {
    }

    public static Object read(Object target, String name) {
        try {
            if (target.getClass().isRecord()) {
                for (RecordComponent component : target.getClass().getRecordComponents()) {
                    if (component.getName().equals(name)) {
                        Method accessor = component.getAccessor();
                        accessor.setAccessible(true);
                        return accessor.invoke(target);
                    }
                }
            }
            String stem = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
            for (String prefix : new String[] {"get", "is"}) {
                try {
                    Method getter = target.getClass().getMethod(prefix + stem);
                    return getter.invoke(target);
                } catch (NoSuchMethodException _) {
                    // try the next prefix
                }
            }
            throw new IllegalArgumentException("no property " + name);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
