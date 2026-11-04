package io.powerj.cmdlets;

import java.lang.reflect.Method;
import java.util.List;

import io.powerj.text.RecordView;

/**
 * The accessors of a record class, made accessible by this module and read once per class and not at every use
 * (review C-26): {@code Class.getRecordComponents()} builds new objects at each call, about 7 µs, and {@code to-csv},
 * {@code to-json} and {@code join} ask for every column of every object. The names and the sizes ({@code @Bytes}) are
 * those of {@link RecordView}; the accessors stay here because a module can make accessible only what is open or
 * exported to it (a record of a third-party module is opened to {@code io.powerj.core} only), so the one that reads
 * must be the one that makes them accessible, and {@code powerj-text} cannot do it for the others.
 */
final class RecordShape {

    private static final ClassValue<RecordShape> SHAPES = new ClassValue<>() {
        @Override
        protected RecordShape computeValue(Class<?> type) {
            return new RecordShape(type);
        }
    };

    private final RecordView view;
    private final Method[] accessors;

    private RecordShape(Class<?> type) {
        view = RecordView.of(type);
        accessors = new Method[view.components().size()];
        for (int i = 0; i < accessors.length; i++) {
            accessors[i] = view.components().get(i).recordComponent().getAccessor();
            accessors[i].trySetAccessible();
        }
    }

    /** The shape of a record class; the same one every time. */
    static RecordShape of(Class<?> recordType) {
        return SHAPES.get(recordType);
    }

    /** Names of the components, in declaration order (unmodifiable). */
    List<String> names() {
        return view.names();
    }

    /** Position of the component called {@code name}, or -1. */
    int indexOf(String name) {
        return view.indexOf(name);
    }

    /** Accessor of the component at {@code index}, already made accessible when it can be. */
    Method accessor(int index) {
        return accessors[index];
    }

    /** {@code true} if the component called {@code name} is a size ({@code @Bytes}). */
    boolean isBytes(String name) {
        return view.isBytes(name);
    }
}
