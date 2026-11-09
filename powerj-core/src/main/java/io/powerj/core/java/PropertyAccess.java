package io.powerj.core.java;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import io.powerj.api.Row;
import io.powerj.core.error.PjError;
import io.powerj.core.error.PjException;
import io.powerj.core.i18n.Messages;
import io.powerj.core.lang.Accessor;

/**
 * {@code .property} and {@code [index]} access on a value (specification FR-28). A property is resolved in
 * this order: record component, getter {@code getNom()}/{@code isNom()}, public field, {@code Map} key.
 * <b>The case of the name matters</b>, everywhere: {@code size} is not {@code Size}. A getter is found by the name of its
 * property, the one that the completion lists: {@code getName()} is {@code name}, {@code getURL()} is {@code URL}.
 * The property always applies to the object itself: for each element of a list, write
 * {@code list*.name}. It is also how a command reads the properties of the objects it receives
 * ({@code CmdletContext.readProperty}): the engine alone reflects on a record.
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

    /**
     * The value of the property called {@code name}: a record component, a getter, a public field, a key of a {@code Map}.
     * {@code name} is one literal name ({@code first name} or {@code a.b} are keys, not paths), and the case matters. An
     * interrupted getter ends the command, it is not an error of the object.
     *
     * @throws PjException if the object has no such property (the message says which ones it has, or the same name in another
     *                     case) or if reading it fails
     */
    public static Object property(Object target, String name) {
        if (target == null) {
            throw new PjException(Messages.get("property.onNull", name));
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(name);
        }
        var direct = directProperty(target, name, false);
        if (direct.found()) {
            return direct.value();
        }
        List<String> hints = new ArrayList<>();
        String otherCase = sameNameInAnotherCase(target, name);
        if (otherCase != null) {
            hints.add(Messages.get("property.hint.case", otherCase));
        }
        if (target instanceof Collection<?> || target.getClass().isArray()) {
            hints.add(Messages.get("property.hint.each", name));
        }
        if (JavaInvoker.hasNoArgMethod(target.getClass(), name)) {
            hints.add(Messages.get("property.hint.method", name));
        }
        if (hints.isEmpty()) {
            List<String> known = Members.of(target).stream().filter(m -> m.kind() != Members.Kind.METHOD)
                    .map(Members.Member::name).limit(12).toList();
            if (!known.isEmpty()) {
                hints.add(Messages.get("property.hint.properties", String.join(", ", known)));
            }
        }
        throw new PjException(hints.isEmpty() ? Messages.get("property.missing", typeName(target), name)
                : Messages.get("property.missing.hints", typeName(target), name,
                        String.join(Messages.get("property.hint.separator"), hints)));
    }

    /**
     * The value of a property, read only where reading it cannot do anything: a key of a map of the JDK or a
     * {@link Row}, a record component, a public field, a getter that {@link PreviewPolicy} knows to be pure. Any
     * other getter is not called. For completion, which promises to run no method: {@code null} means that the
     * property is null or cannot be read without risk, and the caller then goes by the signature.
     */
    public static Object peek(Object target, String name) {
        try {
            if (target instanceof Map<?, ?> map) {
                return plainMap(map) ? map.get(name) : null;
            }
            var direct = directProperty(target, name, true);
            return direct.found() ? direct.value() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The properties of a class by their name in lower case, for the hint of a name written in another case. Worked out once per
     * class (and freed with it): a command that skips the objects it cannot read asks for it once per object.
     */
    private static final ClassValue<Map<String, String>> PROPERTIES_BY_LOWER_CASE = new ClassValue<>() {
        @Override
        protected Map<String, String> computeValue(Class<?> type) {
            Map<String, String> byLowerCase = new HashMap<>();
            for (Members.Member member : Members.ofType(type)) {
                if (member.kind() != Members.Kind.METHOD) {
                    byLowerCase.putIfAbsent(member.name().toLowerCase(Locale.ROOT), member.name());
                }
            }
            return Map.copyOf(byLowerCase);
        }
    };

    /** The property of {@code target} that {@code name} spells in another case, or {@code null}: {@code Size} for {@code size}. */
    private static String sameNameInAnotherCase(Object target, String name) {
        String known = PROPERTIES_BY_LOWER_CASE.get(target.getClass()).get(name.toLowerCase(Locale.ROOT));
        return known != null && !known.equals(name) ? known : null;
    }

    private static boolean plainMap(Map<?, ?> map) {
        return map instanceof Row || "java.base".equals(map.getClass().getModule().getName());
    }

    /** Readable name: {@code List} rather than an internal JDK class ({@code UnmodifiableRandomAccessList}). */
    private static String typeName(Object value) {
        return switch (value) {
            case List<?> _ -> "List";
            case Set<?> _ -> "Set";
            case Collection<?> _ -> "Collection";
            default -> value.getClass().getSimpleName();
        };
    }

    private record Lookup(boolean found, Object value) {
        static final Lookup MISSING = new Lookup(false, null);
    }

    /** {@code pureOnly}: a getter that {@link PreviewPolicy} does not allow is left alone, as if it was not there. */
    private static Lookup directProperty(Object target, String name, boolean pureOnly) {
        Class<?> type = target.getClass();
        try {
            if (type.isRecord()) {
                Method accessor = RecordShape.of(type).accessor(name);
                if (accessor != null) {
                    return new Lookup(true, accessor.invoke(target));
                }
            }
            var getter = Members.getter(type, name);
            if (getter.isPresent()) {
                if (pureOnly && !PreviewPolicy.allows(getter.get())) {
                    return Lookup.MISSING;
                }
                PreviewGuard.checkCall(getter.get()); // record components above are always read (FR-66)
                return new Lookup(true, getter.get().invoke(target));
            }
            var field = Members.field(type, name);
            if (field.isPresent()) {
                return new Lookup(true, field.get().get(target));
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CancellationException cancelled) {
                throw cancelled; // a getter that was interrupted: the command is being stopped, not unreadable
            }
            throw new PjException(PjError.of(Messages.get("property.readFailed", name, cause), cause));
        } catch (ReflectiveOperationException e) {
            throw new PjException(PjError.of(Messages.get("property.readImpossible", name, e), e));
        }
        return Lookup.MISSING;
    }

    public static Object index(Object target, int index) {
        return switch (target) {
            case null -> throw new PjException(Messages.get("index.onNull", index));
            case List<?> list -> list.get(position(index, list.size()));
            case CharSequence text -> String.valueOf(text.charAt(position(index, text.length())));
            case Object array when array.getClass().isArray() -> Array.get(array, position(index, Array.getLength(array)));
            default -> throw new PjException(Messages.get("index.notIndexable", target.getClass().getSimpleName()));
        };
    }

    private static int position(int index, int size) {
        int position = index < 0 ? size + index : index;
        if (position < 0 || position >= size) {
            throw new PjException(Messages.get("index.outOfBounds", index, size));
        }
        return position;
    }
}
