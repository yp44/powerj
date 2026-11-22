package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The stop protocol of the parallel work of {@link FileWalker} and {@link FileTrees} (#96): an unchecked exception of a
 * worker is thrown back to the caller, and an interruption while the workers wait on a full queue ends every thread.
 * These cases go through the code that the two classes share in substance (queue, {@code pending}, {@code stopped}); they
 * pin its behavior before it is moved into one engine.
 */
class ParallelStopTest {

    /** Capacity of the queue between the workers and the calling thread: the tree must hold more entries. */
    private static final int QUEUE_CAPACITY = ParallelEngine.QUEUE_CAPACITY;
    /** Time allowed to a stopped walk to end all its threads and return. */
    private static final Duration BOUND = Duration.ofSeconds(10);

    @TempDir
    Path tmp;

    private final FaultyFileSystem faulty = new FaultyFileSystem();
    private final List<String> errors = new ArrayList<>();

    /** A wide tree: 10 folders, each with a subfolder, and 40 files in each pair: 420 entries in all. */
    @BeforeEach
    void tree() throws IOException {
        assertThat(QUEUE_CAPACITY + 1).as("entries the workers must produce to fill the queue").isLessThan(420);
        for (int d = 0; d < 10; d++) {
            Path dir = Files.createDirectories(tmp.resolve("w/d" + d + "/sub"));
            for (int f = 0; f < 20; f++) {
                Files.writeString(dir.resolve("f" + f), "x" + f);
                Files.writeString(dir.getParent().resolve("g" + f), "y" + f);
            }
        }
    }

    private void error(Path path, IOException e) {
        errors.add(path + ": " + e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void anUncheckedExceptionOfAWorkerStopsTheRemovalAndIsThrownBack(int parallelism) {
        faulty.failOperation(tmp.resolve("w/d3/g7"), new IllegalStateException("injected"));
        Path root = faulty.wrap(tmp.resolve("w"));

        Throwable thrown = callWithin(() -> FileTrees.delete(root, parallelism, false, _ -> { }, this::error));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("injected");
        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("w/d3/g7")).exists();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void anUncheckedExceptionOfAWorkerStopsTheCopyAndIsThrownBack(int parallelism) {
        faulty.failOperation(tmp.resolve("w/d5/sub/f2"), new IllegalStateException("injected"));
        Path source = faulty.wrap(tmp.resolve("w"));
        Path target = faulty.wrap(tmp.resolve("copy"));

        Throwable thrown = callWithin(() -> FileTrees.copy(source, target, parallelism, false, this::error));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("injected");
        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("copy/d5/sub/f2")).doesNotExist();
    }

    @Test
    void anInterruptWhileTheWorkersWaitOnAFullQueueEndsAParallelWalk() {
        AtomicInteger visits = new AtomicInteger();
        FileWalker walker = FileWalker.builder().parallelism(4).build();
        AtomicBoolean interrupted = new AtomicBoolean();

        Throwable thrown = callWithin(() -> {
            try {
                walker.walk(tmp.resolve("w"), (path, attributes) -> {
                    visits.incrementAndGet();
                    return path;
                }, _ -> waitThenInterrupt(() -> visits.get() > QUEUE_CAPACITY + 1), error -> errors.add(error.toString()));
            } finally {
                interrupted.set(Thread.interrupted());
            }
        });

        assertThat(thrown).isInstanceOf(CancellationException.class);
        assertThat(interrupted).isTrue();
        assertThat(visits.get()).isLessThan(420); // stopped before the end
    }

    @Test
    void anInterruptWhileTheWorkersWaitOnAFullQueueEndsAParallelRemoval() {
        Path root = faulty.wrap(tmp.resolve("w"));
        AtomicBoolean interrupted = new AtomicBoolean();

        Throwable thrown = callWithin(() -> {
            try {
                FileTrees.delete(root, 4, false,
                        _ -> waitThenInterrupt(() -> faulty.deletions() > QUEUE_CAPACITY + 1), this::error);
            } finally {
                interrupted.set(Thread.interrupted());
            }
        });

        assertThat(thrown).isInstanceOf(CancellationException.class);
        assertThat(interrupted).isTrue();
        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("w")).exists(); // stopped before the end
    }

    /**
     * In the consumer, called in the calling thread: on the first result, waits until the workers have produced more
     * than the queue holds (so at least one of them waits on the full queue), then interrupts the calling thread, as
     * Ctrl+C does. The next read of the queue sees the interruption.
     */
    private static void waitThenInterrupt(BooleanSupplier queueFull) {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!queueFull.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the workers did not fill the queue");
            }
            Thread.onSpinWait();
        }
        Thread.currentThread().interrupt();
    }

    @FunctionalInterface
    private interface Call {
        void run() throws Exception;
    }

    /**
     * Runs the call in a new thread and returns what it threw ({@code null} for nothing); fails if it does not return
     * within {@link #BOUND}. A parallel walk returns only once its executor is closed, so returning proves that every
     * worker has ended.
     */
    private static Throwable callWithin(Call call) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                call.run();
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "parallel-stop-test");
        caller.start();
        try {
            caller.join(BOUND);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        if (caller.isAlive()) {
            caller.interrupt();
            throw new AssertionError("the work did not end within " + BOUND);
        }
        return thrown.get();
    }
}
