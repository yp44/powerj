package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Review C-14: looking a simple name up while an import is added. The lookup used to compute under the lock of a
 * {@code ConcurrentHashMap} bin and then take the monitor of the object; {@code addImport} held the monitor and cleared
 * the map: two locks taken in opposite orders.
 */
class JavaClassesConcurrencyTest {

    @Test
    void lookingNamesUpWhileImportsAreAddedNeverBlocks() throws Exception {
        var classes = new JavaClasses();
        var stop = new AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            int seed = t;
            threads.add(Thread.ofPlatform().daemon().name("lookup-" + t).start(() -> {
                try {
                    for (int i = 0; !stop.get(); i++) {
                        classes.simpleClass("Name" + (i + seed) % 40);
                        classes.simpleClass("List");
                    }
                } catch (Throwable e) {
                    failure.set(e);
                }
            }));
        }
        threads.add(Thread.ofPlatform().daemon().name("imports").start(() -> {
            try {
                String[] targets = {"java.security.*", "java.util.logging.*", "java.util.zip.*", "java.sql.*"};
                for (int i = 0; !stop.get(); i++) {
                    classes.addImport(targets[i % targets.length]);
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        }));

        Thread.sleep(1500);
        stop.set(true);
        long deadline = System.nanoTime() + 10_000_000_000L;
        for (Thread thread : threads) {
            thread.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
        }

        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        assertThat(deadlocked).as("deadlocked threads").isNull();
        assertThat(threads).as("threads still running").noneMatch(Thread::isAlive);
        assertThat(failure.get()).isNull();
    }

    @Test
    void aNameIsResolvedWithTheImportsThatExistWhenItIsAsked() {
        var classes = new JavaClasses();
        assertThat(classes.simpleClass("Level")).isEmpty(); // java.util.logging is not imported by default

        classes.addImport("java.util.logging.*");

        assertThat(classes.simpleClass("Level")).hasValue(java.util.logging.Level.class);
    }

    @Test
    void anImportOfAClassOverridesNothingAndTheListIsInOrder() {
        var classes = new JavaClasses();
        classes.addImport("java.util.logging.*");
        classes.addImport("java.util.logging.Level");

        assertThat(classes.imports()).endsWith("java.util.logging.*", "java.util.logging.Level");
        assertThat(classes.imports()).startsWith("java.lang.*");
    }
}
