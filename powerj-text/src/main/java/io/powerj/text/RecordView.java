package io.powerj.text;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.powerj.api.Bytes;
import io.powerj.api.Display;
import io.powerj.api.Highlight;

/**
 * How the output contract of a record class is read, once per class: the columns that {@link Display} names, the sizes
 * that {@link Bytes} marks and the spans that {@link Highlight} points at. The terminal, the grid and the exports each
 * need them, and each used to interpret the annotations itself (#108).
 * <p>
 * It holds <b>structure, not accessors</b>. Reading the value of a component needs an accessor made accessible, and that
 * is allowed or not to the module that asks: a record of a third-party module is opened to {@code io.powerj.core} only,
 * so each module keeps the accessors it can read ({@link RecordComponent#getAccessor()} on the component given here).
 */
public final class RecordView {

    /**
     * A component of a record.
     *
     * @param name            its name
     * @param recordComponent the component itself, to read its type, its accessor or its generic type
     * @param bytes           it is a size ({@link Bytes})
     * @param highlight       the component of the same record that holds the {@code List<Span>} to highlight in this one
     *                        ({@link Highlight}), or {@code null}
     */
    public record Component(String name, RecordComponent recordComponent, boolean bytes, RecordComponent highlight) {
    }

    private static final ClassValue<RecordView> VIEWS = new ClassValue<>() {
        @Override
        protected RecordView computeValue(Class<?> type) {
            return new RecordView(type);
        }
    };

    private final List<Component> components;
    private final List<String> names;
    private final Map<String, Integer> indexes = new HashMap<>();
    private final boolean declaresDisplay;
    private final List<Component> displayed;

    private RecordView(Class<?> type) {
        RecordComponent[] declared = type.getRecordComponents();
        List<Component> all = new ArrayList<>(declared.length);
        List<String> allNames = new ArrayList<>(declared.length);
        for (int i = 0; i < declared.length; i++) {
            RecordComponent c = declared[i];
            all.add(new Component(c.getName(), c, c.isAnnotationPresent(Bytes.class), highlightOf(c, declared)));
            allNames.add(c.getName());
            indexes.put(c.getName(), i); // the names of the components of a record are all different
        }
        this.components = List.copyOf(all);
        this.names = List.copyOf(allNames);
        Display display = type.getAnnotation(Display.class);
        this.declaresDisplay = display != null;
        List<Component> shown = new ArrayList<>();
        if (display != null) {
            for (String name : display.columns()) {
                Integer index = indexes.get(name);
                if (index != null) {
                    shown.add(components.get(index));
                }
            }
        }
        this.displayed = List.copyOf(shown);
    }

    private static RecordComponent highlightOf(RecordComponent component, RecordComponent[] all) {
        Highlight annotation = component.getAnnotation(Highlight.class);
        if (annotation == null) {
            return null;
        }
        for (RecordComponent other : all) {
            if (other.getName().equals(annotation.value()) && other.getType() == List.class) {
                return other;
            }
        }
        return null;
    }

    /**
     * The view of a record class; the same one every time.
     *
     * @throws IllegalArgumentException if {@code recordType} is not a record class
     */
    public static RecordView of(Class<?> recordType) {
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException("not a record: " + recordType.getName());
        }
        return VIEWS.get(recordType);
    }

    /** The components in declaration order (unmodifiable). */
    public List<Component> components() {
        return components;
    }

    /** The names of the components in declaration order (unmodifiable). */
    public List<String> names() {
        return names;
    }

    /** Position of the component called {@code name} (the case matters), or -1. */
    public int indexOf(String name) {
        return indexes.getOrDefault(name, -1);
    }

    /** The component called {@code name} (the case matters), or {@code null}. */
    public Component component(String name) {
        Integer index = indexes.get(name);
        return index == null ? null : components.get(index);
    }

    /** {@code true} if the component called {@code name} is a size ({@link Bytes}). */
    public boolean isBytes(String name) {
        Component component = component(name);
        return component != null && component.bytes();
    }

    /** {@code true} if the record class carries {@link Display}, even if none of its columns exists. */
    public boolean declaresDisplay() {
        return declaresDisplay;
    }

    /**
     * The columns of {@link Display} that exist, in its order (a column named twice is listed twice; one that does not
     * exist is left out); empty if the class has no {@code @Display} or if none of its columns exists. Unmodifiable.
     */
    public List<Component> displayed() {
        return displayed;
    }
}
