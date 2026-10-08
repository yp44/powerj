package io.powerj.api;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;

/**
 * Unmodifiable list emitted as a single block: unlike other collections, it is <b>not</b>
 * unrolled between two pipeline stages (specification FR-30b, FR-36d). This is what
 * {@code collect} produces: the next stage receives the whole list, as a single object.
 * <pre>
 * ls -r | collect | map { l -> l.size() }
 * </pre>
 *
 * @param <T> element type
 */
public final class Collected<T> extends AbstractList<T> implements RandomAccess {

    private final List<T> items;

    /** Copies the elements ({@code null} allowed). */
    public Collected(Collection<? extends T> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    @Override
    public T get(int index) {
        return items.get(index);
    }

    @Override
    public int size() {
        return items.size();
    }
}
