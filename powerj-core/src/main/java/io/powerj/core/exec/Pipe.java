package io.powerj.core.exec;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Liaison entre deux étapes d'un pipeline : file bornée (l'étape rapide attend la lente, la mémoire reste
 * constante), fin de flux, et arrêt demandé par l'étape suivante quand elle ne lit plus.
 */
final class Pipe implements Source {

    /** Représente {@code null} dans la file. */
    private static final Object NULL = new Object();

    private static final int CAPACITY = 256;

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(CAPACITY);
    private volatile boolean aborted;
    private final List<Runnable> onAbort = new CopyOnWriteArrayList<>();

    /** Action exécutée quand le lecteur abandonne : arrêter l'écrivain (fil interrompu, process arrêtés). */
    void onAbort(Runnable action) {
        onAbort.add(action);
        if (aborted) {
            action.run();
        }
    }

    boolean aborted() {
        return aborted;
    }

    /**
     * Ajoute un objet, en attendant de la place.
     *
     * @throws CancellationException si le lecteur a abandonné ou si le fil est interrompu
     */
    void put(Object value) {
        if (aborted) {
            throw new CancellationException("pipeline fermé par l'étape suivante");
        }
        try {
            queue.put(value == null ? NULL : value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("pipeline interrompu");
        }
    }

    /** Ajoute un objet en déroulant les collections, tableaux, flux et optionnels (FR-30b). */
    void putUnrolled(Object value) {
        unroll(value, this);
    }

    /** Signale la fin du flux (sans effet si le lecteur a abandonné). */
    void close() {
        if (!aborted) {
            put(Source.END);
        }
    }

    @Override
    public Object next() throws InterruptedException {
        Object value = queue.take();
        return value == NULL ? null : value;
    }

    @Override
    public void abort() {
        if (!aborted) {
            aborted = true;
            queue.clear();
            onAbort.forEach(Runnable::run);
        }
    }

    /**
     * Émet {@code value}, ou chacun de ses éléments si c'est un {@link Iterable} (sauf {@code Path}), un
     * tableau, un {@link Stream}, un {@link Iterator} ou un {@link Optional}. Les chaînes et les {@code Map}
     * ne sont jamais déroulées.
     */
    static void unroll(Object value, Pipe target) {
        switch (value) {
            case java.nio.file.Path path -> target.put(path);
            case Iterable<?> items -> items.forEach(target::put);
            case Stream<?> stream -> {
                try (stream) {
                    stream.forEach(target::put);
                }
            }
            case Iterator<?> iterator -> iterator.forEachRemaining(target::put);
            case Optional<?> optional -> optional.ifPresent(target::put);
            case Object array when array.getClass().isArray() -> {
                for (int i = 0; i < java.lang.reflect.Array.getLength(array); i++) {
                    target.put(java.lang.reflect.Array.get(array, i));
                }
            }
            case null, default -> target.put(value);
        }
    }

}
