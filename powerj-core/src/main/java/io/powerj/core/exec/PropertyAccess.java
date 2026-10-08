package io.powerj.core.exec;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.powerj.core.lang.Accessor;

/**
 * {@code .propriété} and {@code [index]} access on a value (specification FR-28). A property is resolved in
 * this order: record component, getter {@code getNom()}/{@code isNom()}, public field, {@code Map} key.
 * The property always applies to the object itself: for each element of a list, write
 * {@code liste*.nom}.
 */
public final class PropertyAccess {

    private PropertyAccess() {
    }

    public static Object apply(Object value, List<Accessor> accessors) {
        Object current = value;
        for (Accessor accessor : accessors) {
            current = switch (accessor) {
                case Accessor.Property(var name) -> property(current, name);
                case Accessor.Index(var index) -> index(current, index);
            };
        }
        return current;
    }

    static Object property(Object target, String name) {
        if (target == null) {
            throw new PjException("propriété '" + name + "' sur une valeur nulle");
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(name);
        }
        var direct = directProperty(target, name);
        if (direct.found()) {
            return direct.value();
        }
        List<String> hints = new ArrayList<>();
        if (target instanceof Collection<?> || target.getClass().isArray()) {
            hints.add("pour chaque élément : *." + name);
        }
        if (JavaInvoker.hasNoArgMethod(target.getClass(), name)) {
            hints.add("méthode : " + name + "()");
        }
        if (hints.isEmpty()) {
            List<String> known = Members.of(target).stream().filter(m -> !m.kind().equals("méthode"))
                    .map(Members.Member::name).limit(12).toList();
            if (!known.isEmpty()) {
                hints.add("propriétés : " + String.join(", ", known));
            }
        }
        throw new PjException(typeName(target) + " n'a pas de propriété '" + name + "'"
                + (hints.isEmpty() ? "" : " (" + String.join(" ; ", hints) + ")"));
    }

    /** Readable name: {@code List} rather than an internal JDK class ({@code UnmodifiableRandomAccessList}). */
    private static String typeName(Object value) {
        return switch (value) {
            case List<?> _ -> "List";
            case java.util.Set<?> _ -> "Set";
            case Collection<?> _ -> "Collection";
            default -> value.getClass().getSimpleName();
        };
    }

    private record Lookup(boolean found, Object value) {
        static final Lookup MISSING = new Lookup(false, null);
    }

    private static Lookup directProperty(Object target, String name) {
        Class<?> type = target.getClass();
        try {
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    if (component.getName().equalsIgnoreCase(name)) {
                        Method accessor = component.getAccessor();
                        accessor.trySetAccessible();
                        return new Lookup(true, accessor.invoke(target));
                    }
                }
            }
            var getter = Members.getter(type, name);
            if (getter.isPresent()) {
                return new Lookup(true, getter.get().invoke(target));
            }
            var field = Members.field(type, name);
            if (field.isPresent()) {
                return new Lookup(true, field.get().get(target));
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new PjException(PjError.of("lecture de la propriété '" + name + "' : " + cause, cause));
        } catch (ReflectiveOperationException e) {
            throw new PjException(PjError.of("lecture de la propriété '" + name + "' impossible : " + e, e));
        }
        return Lookup.MISSING;
    }

    static Object index(Object target, int index) {
        return switch (target) {
            case null -> throw new PjException("index [" + index + "] sur une valeur nulle");
            case List<?> list -> list.get(position(index, list.size()));
            case CharSequence text -> String.valueOf(text.charAt(position(index, text.length())));
            case Object array when array.getClass().isArray() -> Array.get(array, position(index, Array.getLength(array)));
            default -> throw new PjException(target.getClass().getSimpleName() + " ne s'indexe pas");
        };
    }

    private static int position(int index, int size) {
        int position = index < 0 ? size + index : index;
        if (position < 0 || position >= size) {
            throw new PjException("index [" + index + "] hors limites (taille " + size + ")");
        }
        return position;
    }
}
