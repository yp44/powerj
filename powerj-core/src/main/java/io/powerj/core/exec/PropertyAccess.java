package io.powerj.core.exec;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        throw new PjException(target.getClass().getSimpleName() + " n'a pas de propriété '" + name + "'");
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
                        return new Lookup(true, component.getAccessor().invoke(target));
                    }
                }
            }
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())
                        && isGetterFor(method, name) && method.getDeclaringClass().getModule().isExported(
                                method.getDeclaringClass().getPackageName())) {
                    return new Lookup(true, method.invoke(target));
                }
            }
            for (var field : type.getFields()) {
                if (field.getName().equalsIgnoreCase(name) && !Modifier.isStatic(field.getModifiers())) {
                    return new Lookup(true, field.get(target));
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new PjException(PjError.of("lecture de la propriété '" + name + "' impossible : " + e, e));
        }
        return Lookup.MISSING;
    }

    private static boolean isGetterFor(Method method, String name) {
        String m = method.getName();
        return m.equalsIgnoreCase("get" + name)
                || (m.equalsIgnoreCase("is" + name)
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class));
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
