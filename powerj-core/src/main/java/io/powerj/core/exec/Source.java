package io.powerj.core.exec;

import java.util.Iterator;

/** Objects received by a pipeline stage. */
interface Source {

    /** End-of-stream marker. */
    Object END = new Object();

    /** Next object, or {@link #END} when there are no more. */
    Object next() throws InterruptedException;

    /** The reading stage stops: the writing stage must stop too. */
    void abort();

    /** Source fed by an iterator (lines of standard input in non-interactive mode). */
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
