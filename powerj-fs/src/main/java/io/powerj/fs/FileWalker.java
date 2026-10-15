package io.powerj.fs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Walk of a file tree (specification FR-64), reusable by any cmdlet.
 * <pre>
 * FileWalker.builder().maxDepth(Integer.MAX_VALUE).parallelism(8).build()
 *         .walk(root, (path, attributes) -> search(path), context::emit, error -> context.error(…));
 * </pre>
 * The {@link Visitor} is called for each entry (file or directory) below a root directory, or for the root
 * itself when it is not a directory. Its result, if not {@code null}, is passed to the result consumer;
 * the errors (inaccessible directory, {@link IOException} of the visitor) go to the error consumer and the
 * walk goes on.
 * <ul>
 *   <li><b>Sequential</b> ({@code parallelism(1)}, the default): everything happens in the calling thread,
 *       depth first, children in the {@link Builder#order order} given; results arrive as the walk goes
 *       (streaming).</li>
 *   <li><b>Parallel</b> ({@code parallelism(n)}, {@code n > 1}): directories are listed and entries visited
 *       in virtual threads, at most {@code n} at the same time (open files). The visitor is then called
 *       concurrently, but the two consumers are still called <b>only in the calling thread</b>: a cmdlet
 *       can pass {@code context::emit} directly. The order of results is free.</li>
 * </ul>
 * Symbolic links to directories are not followed (no infinite loop); a root that is such a link is.
 * Hidden entries are skipped unless {@link Builder#includeHidden(boolean)}; a root is never skipped.
 * If the calling thread is interrupted (Ctrl+C), the walk stops all its threads and throws
 * {@link CancellationException}; an unchecked exception of the visitor or of a consumer stops the walk and
 * is thrown back to the caller.
 */
public final class FileWalker {

    /** Work done on each entry; in parallel mode, called concurrently from several threads. */
    @FunctionalInterface
    public interface Visitor<R> {

        /**
         * @param path       file or directory, resolved against the root
         * @param attributes its attributes (those of the target for a symbolic link)
         * @return result for the consumer, or {@code null} for none
         * @throws IOException non-blocking error, reported to the error consumer
         */
        R visit(Path path, BasicFileAttributes attributes) throws IOException;
    }

    /** Results waiting for the calling thread (backpressure: a slow consumer slows down the walk). */
    private static final int QUEUE_CAPACITY = 256;

    private final int maxDepth;
    private final boolean includeHidden;
    private final int parallelism;
    private final Comparator<Path> order;

    private FileWalker(Builder builder) {
        this.maxDepth = builder.maxDepth;
        this.includeHidden = builder.includeHidden;
        this.parallelism = builder.parallelism;
        this.order = builder.order;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Walks one root: see {@link #walk(Collection, Visitor, Consumer, Consumer)}. */
    public <R> void walk(Path root, Visitor<? extends R> visitor, Consumer<? super R> results,
                         Consumer<? super WalkError> errors) {
        walk(List.of(root), visitor, results, errors);
    }

    /**
     * Walks the roots and returns when every entry has been visited and every result consumed.
     *
     * @param visitor work done on each entry
     * @param results receives the non-{@code null} results, in the calling thread
     * @param errors  receives the non-blocking errors, in the calling thread
     * @throws CancellationException if the calling thread is interrupted
     */
    public <R> void walk(Collection<Path> roots, Visitor<? extends R> visitor, Consumer<? super R> results,
                         Consumer<? super WalkError> errors) {
        Objects.requireNonNull(visitor, "visitor");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(errors, "errors");
        if (parallelism == 1) {
            for (Path root : roots) {
                sequentialRoot(root, visitor, results, errors);
            }
        } else {
            new ParallelWalk<R>(visitor).run(List.copyOf(roots), results, errors);
        }
    }

    // --- Sequential walk ---

    private <R> void sequentialRoot(Path root, Visitor<? extends R> visitor, Consumer<? super R> results,
                                    Consumer<? super WalkError> errors) {
        checkCancelled();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(root, BasicFileAttributes.class);
        } catch (IOException e) {
            errors.accept(WalkError.of(root, e));
            return;
        }
        if (attributes.isDirectory()) {
            sequentialDirectory(root, 0, visitor, results, errors);
        } else {
            sequentialVisit(root, attributes, visitor, results, errors);
        }
    }

    private <R> void sequentialDirectory(Path dir, int depth, Visitor<? extends R> visitor,
                                         Consumer<? super R> results, Consumer<? super WalkError> errors) {
        List<Entry> children;
        try {
            children = children(dir, errors::accept);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt(); // not thrown by errors::accept
            throw new CancellationException();
        }
        if (order != null) {
            children.sort(Comparator.comparing(Entry::path, order));
        }
        for (Entry child : children) {
            checkCancelled();
            sequentialVisit(child.path(), child.attributes(), visitor, results, errors);
            if (child.descend() && depth + 1 < maxDepth) {
                sequentialDirectory(child.path(), depth + 1, visitor, results, errors);
            }
        }
    }

    private static <R> void sequentialVisit(Path path, BasicFileAttributes attributes, Visitor<? extends R> visitor,
                                            Consumer<? super R> results, Consumer<? super WalkError> errors) {
        R result;
        try {
            result = visitor.visit(path, attributes);
        } catch (IOException e) {
            errors.accept(WalkError.of(path, e));
            return;
        }
        if (result != null) {
            results.accept(result);
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    // --- Common to both modes ---

    /**
     * Entry of a directory.
     *
     * @param descend {@code true} for a real directory (not a symbolic link to a directory)
     */
    private record Entry(Path path, BasicFileAttributes attributes, boolean descend) { }

    /** Visible children of a directory; an entry that vanished meanwhile is skipped. */
    private List<Entry> children(Path dir, ErrorSink errors) throws InterruptedException {
        List<Entry> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (includeHidden || !hidden(child)) {
                    Entry entry = entry(child);
                    if (entry != null) {
                        children.add(entry);
                    }
                }
            }
        } catch (IOException e) {
            errors.report(WalkError.of(dir, e));
        }
        return children;
    }

    private static Entry entry(Path path) {
        try {
            var own = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!own.isSymbolicLink()) {
                return new Entry(path, own, own.isDirectory());
            }
            try {
                return new Entry(path, Files.readAttributes(path, BasicFileAttributes.class), false);
            } catch (IOException _) {
                return new Entry(path, own, false); // broken link: described by itself
            }
        } catch (IOException _) {
            return null; // vanished between listing and reading
        }
    }

    private static boolean hidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException _) {
            return false;
        }
    }

    /** Receives an error; may block (parallel mode: full queue). */
    @FunctionalInterface
    private interface ErrorSink {
        void report(WalkError error) throws InterruptedException;
    }

    // --- Parallel walk ---

    /** One parallel walk: workers in virtual threads, results handed over to the calling thread by a queue. */
    private final class ParallelWalk<R> {

        private static final Object END = new Object();

        private record Failure(Throwable exception) { }

        @FunctionalInterface
        private interface Task {
            void run() throws Exception;
        }

        private final Visitor<? extends R> visitor;
        private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        /** Directory listings and visits in progress: bounds the open files. */
        private final Semaphore permits = new Semaphore(parallelism);
        /** Tasks submitted and not finished; the last one to finish signals the end. */
        private final AtomicInteger pending = new AtomicInteger();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private volatile boolean stopped;

        ParallelWalk(Visitor<? extends R> visitor) {
            this.visitor = visitor;
        }

        @SuppressWarnings("unchecked")
        void run(List<Path> roots, Consumer<? super R> results, Consumer<? super WalkError> errors) {
            try (executor) {
                try {
                    submit(() -> roots.forEach(root -> submit(() -> root(root))));
                    while (true) {
                        Object item = queue.take();
                        switch (item) {
                            case Failure(Throwable e) when e instanceof RuntimeException r -> throw r;
                            case Failure(Throwable e) when e instanceof Error error -> throw error;
                            case Failure(Throwable e) -> throw new IllegalStateException(e);
                            case WalkError error -> errors.accept(error);
                            default -> {
                                if (item == END) {
                                    return;
                                }
                                results.accept((R) item);
                            }
                        }
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                } finally {
                    stopped = true;
                    executor.shutdownNow(); // interrupts the workers; close() waits for them
                }
            }
        }

        private void root(Path root) throws InterruptedException {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(root, BasicFileAttributes.class);
            } catch (IOException e) {
                queue.put(WalkError.of(root, e));
                return;
            }
            if (attributes.isDirectory()) {
                directory(root, 0);
            } else {
                visit(root, attributes);
            }
        }

        private void directory(Path dir, int depth) throws InterruptedException {
            List<Entry> children;
            permits.acquire();
            try {
                children = children(dir, queue::put);
            } finally {
                permits.release();
            }
            for (Entry child : children) {
                if (stopped) {
                    return;
                }
                if (child.descend() && depth + 1 < maxDepth) {
                    submit(() -> directory(child.path(), depth + 1));
                }
                visit(child.path(), child.attributes());
            }
        }

        /** Visits an entry in its own virtual thread, as soon as a permit is free. */
        private void visit(Path path, BasicFileAttributes attributes) throws InterruptedException {
            permits.acquire();
            boolean submitted = false;
            try {
                submitted = submit(() -> {
                    try {
                        R result = visitor.visit(path, attributes);
                        if (result != null) {
                            queue.put(result);
                        }
                    } catch (IOException e) {
                        queue.put(WalkError.of(path, e));
                    } finally {
                        permits.release();
                    }
                });
            } finally {
                if (!submitted) {
                    permits.release();
                }
            }
        }

        private boolean submit(Task task) {
            pending.incrementAndGet();
            try {
                executor.execute(() -> {
                    try {
                        task.run();
                    } catch (Throwable e) {
                        fail(e);
                    } finally {
                        if (pending.decrementAndGet() == 0) {
                            signal(END);
                        }
                    }
                });
                return true;
            } catch (RejectedExecutionException _) {
                pending.decrementAndGet(); // walk already stopped
                return false;
            }
        }

        private void fail(Throwable e) {
            if (stopped || e instanceof InterruptedException || e instanceof CancellationException) {
                return; // consequence of the stop
            }
            stopped = true;
            signal(new Failure(e));
        }

        private void signal(Object item) {
            try {
                queue.put(item);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt(); // walk stopped: nobody reads anymore
            }
        }
    }

    /** Settings of a {@link FileWalker}. */
    public static final class Builder {

        private int maxDepth = Integer.MAX_VALUE;
        private boolean includeHidden;
        private int parallelism = 1;
        private Comparator<Path> order;

        private Builder() {
        }

        /**
         * Depth of the walk below a root directory: {@code 1} visits only its children, without entering
         * the subdirectories; by default, no limit.
         */
        public Builder maxDepth(int maxDepth) {
            if (maxDepth < 1) {
                throw new IllegalArgumentException("maxDepth must be at least 1: " + maxDepth);
            }
            this.maxDepth = maxDepth;
            return this;
        }

        /** Also visits hidden files and directories; by default, they are skipped. */
        public Builder includeHidden(boolean includeHidden) {
            this.includeHidden = includeHidden;
            return this;
        }

        /**
         * Number of entries processed at the same time: {@code 1} (default) for a sequential walk in the
         * calling thread, more for a parallel walk in virtual threads.
         */
        public Builder parallelism(int parallelism) {
            if (parallelism < 1) {
                throw new IllegalArgumentException("parallelism must be at least 1: " + parallelism);
            }
            this.parallelism = parallelism;
            return this;
        }

        /** Order of the children of a directory in a sequential walk (ignored in parallel); by default, that of the system. */
        public Builder order(Comparator<Path> order) {
            this.order = order;
            return this;
        }

        public FileWalker build() {
            return new FileWalker(this);
        }
    }
}
