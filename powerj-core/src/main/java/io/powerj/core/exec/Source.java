package io.powerj.core.exec;

import java.util.Iterator;

/** Objets reçus par une étape de pipeline. */
interface Source {

    /** Marque de fin de flux. */
    Object END = new Object();

    /** Prochain objet, ou {@link #END} quand il n'y en a plus. */
    Object next() throws InterruptedException;

    /** L'étape qui lit s'arrête : l'étape qui écrit doit s'arrêter aussi. */
    void abort();

    /** Source alimentée par un itérateur (lignes de l'entrée standard en mode non interactif). */
    static Source of(Iterator<?> iterator) {
        return new Source() {
            @Override
            public synchronized Object next() {
                return iterator.hasNext() ? iterator.next() : END;
            }

            @Override
            public void abort() {
                // l'itérateur reste disponible pour l'instruction suivante
            }
        };
    }
}
