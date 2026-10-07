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
 * Accès {@code .propriété} et {@code [index]} sur une valeur (spécification FR-28). Propriété résolue dans
 * l'ordre : composant de record, getter {@code getNom()}/{@code isNom()}, champ public, clé de {@code Map} ;
 * sur une collection, appliquée à chaque élément.
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
        if (target instanceof Collection<?> collection) {
            List<Object> values = new ArrayList<>(collection.size());
            for (Object element : collection) {
                values.add(property(element, name));
            }
            return values;
        }
        List<String> known = Members.of(target).stream().filter(m -> !m.kind().equals("méthode"))
                .map(Members.Member::name).limit(12).toList();
        throw new PjException(target.getClass().getSimpleName() + " n'a pas de propriété '" + name + "'"
                + (known.isEmpty() ? "" : " (propriétés : " + String.join(", ", known) + ")"));
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
