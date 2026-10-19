package io.powerj.fs;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Removal and copy of a file tree (specification FR-72b), sequential or parallelized with virtual threads.
 * <pre>
 * FileTrees.delete(root, 8, false, removed -> …, (path, e) -> context.error(…));
 * </pre>
 * Symbolic links are never followed: a link is removed or copied as a link, a link to a directory is not entered.
 * Hidden entries are included. Errors ({@link IOException} on an entry) go to the error consumer and the work goes
 * on with the other entries.
 * <ul>
 *   <li><b>Sequential</b> ({@code parallelism == 1}): everything happens in the calling thread, depth first.</li>
 *   <li><b>Parallel</b> ({@code parallelism > 1}): directories are listed and entries removed or copied in virtual
 *       threads, at most {@code parallelism} at the same time. A directory is still removed after its content, and
 *       created before its content is copied. The consumers are called <b>only in the calling thread</b>; the order
 *       of the calls is free.</li>
 * </ul>
 * If the calling thread is interrupted (Ctrl+C), the work stops all its threads and throws
 * {@link CancellationException}; an unchecked exception of a consumer stops the work and is thrown back to the caller.
 */
public final class FileTrees {

    /**
     * An entry removed (or that would be, in a dry run).
     *
     * @param attributes its own attributes (those of the link for a symbolic link), read before the removal
     */
    public record Removed(Path path, BasicFileAttributes attributes) {

        public Removed {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(attributes, "attributes");
        }
    }

    /** Results waiting for the calling thread (backpressure: a slow consumer slows down the work). */
    private static final int QUEUE_CAPACITY = 256;

    private FileTrees() {
    }

    /**
     * Removes a tree, content first; with {@code dryRun}, removes nothing but reports the same entries, in the same
     * order constraints.
     *
     * @param removed receives each entry once removed, in the calling thread
     * @param errors  receives each entry that could not be read or removed, in the calling thread
     * @return {@code true} if no error was reported
     * @throws CancellationException if the calling thread is interrupted
     */
    public static boolean delete(Path root, int parallelism, boolean dryRun, Consumer<? super Removed> removed,
                                 BiConsumer<? super Path, ? super IOException> errors) {
        check(parallelism);
        Objects.requireNonNull(removed, "removed");
        Objects.requireNonNull(errors, "errors");
        int[] failures = {0};
        BiConsumer<Path, IOException> counted = (path, e) -> {
            failures[0]++;
            errors.accept(path, e);
        };
        if (parallelism == 1) {
            sequentialDelete(root, dryRun, removed, counted);
        } else {
            new Work(parallelism).run(work -> work.deleteRoot(root, dryRun), removed, counted);
        }
        return failures[0] == 0;
    }

    /**
     * Copies a file, a link or a tree to {@code target}, keeping the attributes; links are copied as links. With
     * {@code replace}, existing files are replaced and existing directories are merged.
     *
     * @param errors receives each entry that could not be read or copied (with the path of the copy for a failed
     *               copy), in the calling thread
     * @return {@code true} if no error was reported
     * @throws CancellationException if the calling thread is interrupted
     */
    public static boolean copy(Path source, Path target, int parallelism, boolean replace,
                               BiConsumer<? super Path, ? super IOException> errors) {
        check(parallelism);
        Objects.requireNonNull(errors, "errors");
        int[] failures = {0};
        BiConsumer<Path, IOException> counted = (path, e) -> {
            failures[0]++;
            errors.accept(path, e);
        };
        CopyOption[] options = replace
                ? new CopyOption[] {StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.REPLACE_EXISTING}
                : new CopyOption[] {StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS};
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.copy(source, target, options);
            } catch (IOException e) {
                counted.accept(target, e);
            }
        } else if (parallelism == 1) {
            sequentialCopy(source, target, replace, options, counted);
        } else {
            new Work(parallelism).run(work -> work.copyDirectory(source, target, replace, options), _ -> { },
                    counted);
        }
        return failures[0] == 0;
    }

    private static void check(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be at least 1: " + parallelism);
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    // --- Sequential ---

    private static void sequentialDelete(Path root, boolean dryRun, Consumer<? super Removed> removed,
                                         BiConsumer<Path, IOException> errors) {
        Deque<BasicFileAttributes> directories = new ArrayDeque<>(); // attributes of the directories being walked
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    checkCancelled();
                    directories.push(attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    delete(file, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    errors.accept(file, e);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    if (e != null) {
                        errors.accept(dir, e);
                    }
                    delete(dir, directories.pop());
                    return FileVisitResult.CONTINUE;
                }

                private void delete(Path entry, BasicFileAttributes attributes) {
                    checkCancelled();
                    try {
                        if (!dryRun) {
                            Files.delete(entry);
                        }
                        removed.accept(new Removed(entry, attributes));
                    } catch (IOException e) {
                        errors.accept(entry, e);
                    }
                }
            });
        } catch (IOException e) {
            errors.accept(root, e);
        }
    }

    private static void sequentialCopy(Path source, Path target, boolean replace, CopyOption[] options,
                                       BiConsumer<Path, IOException> errors) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    checkCancelled();
                    Path copy = target.resolve(source.relativize(dir).toString());
                    try {
                        createDirectory(dir, copy, replace);
                        return FileVisitResult.CONTINUE;
                    } catch (IOException e) {
                        errors.accept(copy, e);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    checkCancelled();
                    Path copy = target.resolve(source.relativize(file).toString());
                    try {
                        Files.copy(file, copy, options);
                    } catch (IOException e) {
                        errors.accept(copy, e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    errors.accept(file, e);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errors.accept(source, e);
        }
    }

    /** Creates the copy of a directory with its attributes; with {@code replace}, an existing one is merged. */
    private static void createDirectory(Path dir, Path copy, boolean replace) throws IOException {
        if (!(replace && Files.isDirectory(copy, LinkOption.NOFOLLOW_LINKS))) {
            Files.copy(dir, copy, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    // --- Parallel ---

    /** An entry of a directory, with its own attributes (links not followed). */
    private record Entry(Path path, BasicFileAttributes attributes) {
    }

    /** An error to hand over to the calling thread. */
    private record EntryError(Path path, IOException exception) {
    }

    /** An unchecked exception or error of a worker, thrown back in the calling thread. */
    private record Failure(Throwable exception) {
    }

    /**
     * A directory being removed: it is removed when its last child is done; then it counts as done for its parent.
     * {@code remaining} starts at 1 while the children are submitted, so that the directory is not removed early.
     */
    private record Directory(Path path, BasicFileAttributes attributes, Directory parent, AtomicInteger remaining) {

        Directory(Path path, BasicFileAttributes attributes, Directory parent) {
            this(path, attributes, parent, new AtomicInteger(1));
        }
    }

    /**
     * One parallel removal or copy: workers in virtual threads, at most {@code parallelism} listings, removals or
     * copies at the same time; results and errors handed over to the calling thread by a bounded queue.
     */
    private static final class Work {

        private static final Object END = new Object();

        @FunctionalInterface
        private interface Task {
            void run() throws Exception;
        }

        private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final Semaphore permits;
        /** Tasks submitted and not finished; the last one to finish signals the end. */
        private final AtomicInteger pending = new AtomicInteger();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private volatile boolean stopped;

        Work(int parallelism) {
            this.permits = new Semaphore(parallelism);
        }

        @FunctionalInterface
        interface Start {
            void run(Work work) throws Exception;
        }

        void run(Start start, Consumer<? super Removed> removed, BiConsumer<Path, IOException> errors) {
            try (executor) {
                try {
                    submit(() -> start.run(this));
                    while (true) {
                        Object item = queue.take();
                        switch (item) {
                            case Failure(Throwable e) when e instanceof RuntimeException r -> throw r;
                            case Failure(Throwable e) when e instanceof Error error -> throw error;
                            case Failure(Throwable e) -> throw new IllegalStateException(e);
                            case EntryError(Path path, IOException e) -> errors.accept(path, e);
                            case Removed entry -> removed.accept(entry);
                            default -> {
                                if (item == END) {
                                    return;
                                }
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

        // removal

        void deleteRoot(Path root, boolean dryRun) throws InterruptedException {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                queue.put(new EntryError(root, e));
                return;
            }
            if (attributes.isDirectory()) {
                deleteDirectory(new Directory(root, attributes, null), dryRun);
            } else {
                remove(root, attributes, dryRun);
            }
        }

        /** Lists a directory and submits its children; the directory itself is removed by its last child. */
        private void deleteDirectory(Directory dir, boolean dryRun) throws InterruptedException {
            List<Entry> children = children(dir.path());
            if (children == null) { // not listed: kept, as in a sequential walk; done for its parent
                if (dir.parent() != null) {
                    done(dir.parent(), dryRun);
                }
                return;
            }
            for (Entry child : children) {
                if (stopped) {
                    return;
                }
                dir.remaining().incrementAndGet();
                if (child.attributes().isDirectory()) {
                    submit(() -> deleteDirectory(new Directory(child.path(), child.attributes(), dir), dryRun));
                } else {
                    permits.acquire(); // bounds the waiting tasks too: the listing waits for a free permit
                    boolean submitted = false;
                    try {
                        submitted = submit(() -> {
                            try {
                                removeHeld(child.path(), child.attributes(), dryRun);
                            } finally {
                                permits.release();
                            }
                            done(dir, dryRun);
                        });
                    } finally {
                        if (!submitted) {
                            permits.release();
                        }
                    }
                }
            }
            done(dir, dryRun); // the initial count: all the children are submitted
        }

        /** A child of {@code dir} is done: the last one removes {@code dir}, which is then done for its parent. */
        private void done(Directory dir, boolean dryRun) throws InterruptedException {
            for (Directory current = dir; current != null && current.remaining().decrementAndGet() == 0;
                 current = current.parent()) {
                remove(current.path(), current.attributes(), dryRun);
            }
        }

        private void remove(Path path, BasicFileAttributes attributes, boolean dryRun) throws InterruptedException {
            permits.acquire();
            try {
                removeHeld(path, attributes, dryRun);
            } finally {
                permits.release();
            }
        }

        /** Removes an entry; the caller holds a permit. */
        private void removeHeld(Path path, BasicFileAttributes attributes, boolean dryRun)
                throws InterruptedException {
            if (stopped) {
                return;
            }
            try {
                if (!dryRun) {
                    Files.delete(path);
                }
                queue.put(new Removed(path, attributes));
            } catch (IOException e) {
                queue.put(new EntryError(path, e));
            }
        }

        // copy

        /** Creates the copy of a directory, then submits its children; a directory not created skips its content. */
        void copyDirectory(Path dir, Path copy, boolean replace, CopyOption[] options) throws InterruptedException {
            List<Entry> children = children(dir);
            if (children == null) {
                return; // not listed: not copied, as in a sequential walk
            }
            permits.acquire();
            try {
                createDirectory(dir, copy, replace);
            } catch (IOException e) {
                queue.put(new EntryError(copy, e));
                return;
            } finally {
                permits.release();
            }
            for (Entry child : children) {
                if (stopped) {
                    return;
                }
                Path childCopy = copy.resolve(child.path().getFileName().toString());
                if (child.attributes().isDirectory()) {
                    submit(() -> copyDirectory(child.path(), childCopy, replace, options));
                } else {
                    permits.acquire();
                    boolean submitted = false;
                    try {
                        submitted = submit(() -> {
                            try {
                                if (!stopped) {
                                    Files.copy(child.path(), childCopy, options);
                                }
                            } catch (IOException e) {
                                queue.put(new EntryError(childCopy, e));
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
            }
        }

        // common

        /**
         * Children of a directory with their own attributes, or {@code null} (error reported) if it cannot be listed;
         * an entry that vanished meanwhile is skipped.
         */
        private List<Entry> children(Path dir) throws InterruptedException {
            List<Entry> children = new ArrayList<>();
            permits.acquire();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    try {
                        children.add(new Entry(child, Files.readAttributes(child, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS)));
                    } catch (NoSuchFileException _) {
                        // vanished between listing and reading
                    } catch (IOException e) {
                        queue.put(new EntryError(child, e));
                    }
                }
            } catch (DirectoryIteratorException e) {
                queue.put(new EntryError(dir, e.getCause())); // failed while listing: the entries read are kept
            } catch (IOException e) {
                queue.put(new EntryError(dir, e));
                return null;
            } finally {
                permits.release();
            }
            return children;
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
                pending.decrementAndGet(); // work already stopped
                return false;
            }
        }

        private void fail(Throwable e) {
            if (stopped || e instanceof InterruptedException || e instanceof CancellationException) {
                return; // consequence of the stop
            }
            stopped = true;
            deliver(new Failure(e)); // read by the calling thread, which waits for it
        }

        /** Signals the end, unless the work is stopped: nobody reads anymore, and a full queue would block. */
        private void signal(Object item) {
            if (!stopped) {
                deliver(item);
            }
        }

        private void deliver(Object item) {
            try {
                queue.put(item);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt(); // work stopped: nobody reads anymore
            }
        }
    }
}
