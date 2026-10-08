package io.powerj.api;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;

/**
 * Liste non modifiable émise d'un seul bloc : contrairement aux autres collections, elle n'est <b>pas</b>
 * déroulée entre deux étapes de pipeline (spécification FR-30b, FR-36d). C'est ce que produit
 * {@code collect} : l'étape suivante reçoit la liste entière, en un seul objet.
 * <pre>
 * ls -r | collect | map { l -> l.size() }
 * </pre>
 *
 * @param <T> type des éléments
 */
public final class Collected<T> extends AbstractList<T> implements RandomAccess {

    private final List<T> items;

    /** Copie des éléments ({@code null} accepté). */
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
