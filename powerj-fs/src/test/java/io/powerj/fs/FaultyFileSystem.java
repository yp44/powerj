package io.powerj.fs;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The file system of the machine seen through a wrapper that can fail in the middle of the listing of a folder, as a
 * flaky network file system does: the entries already read are returned, then the iteration of the folder throws
 * {@link DirectoryIteratorException}, which no real folder can be made to do on every system. Everything else goes to the
 * real file system. It can also (#96) refuse to open a folder; make the removal, the copy or the creation of a chosen
 * entry throw (an {@link IOException}, or an unchecked exception as a faulty provider would); count the removals done;
 * and measure how many listings, removals, copies and creations of folders run at the same time.
 * <pre>
 * var faulty = new FaultyFileSystem();
 * faulty.failListing(folder, 2);          // the third entry of folder cannot be read
 * faulty.failOpening(other);              // other cannot be listed at all
 * faulty.failOperation(file, new IllegalStateException("x")); // its removal or copy throws
 * Path root = faulty.wrap(tmp);           // walk root, not tmp
 * </pre>
 */
final class FaultyFileSystem extends FileSystem {

    private final FileSystem real = FileSystems.getDefault();
    private final FileSystemProvider realProvider = real.provider();
    private final Provider provider = new Provider();
    /** Entries read before the failure, by folder (absolute, normalized, as the real file system sees it). */
    private final Map<Path, Integer> failAfter = new ConcurrentHashMap<>();
    /** Exceptions thrown by the removal or the copy of an entry (absolute, normalized, real). */
    private final Map<Path, Exception> failOperation = new ConcurrentHashMap<>();
    /** Folders that cannot be opened (absolute, normalized, real). */
    private final Set<Path> failOpening = ConcurrentHashMap.newKeySet();
    private final AtomicInteger deletions = new AtomicInteger();
    private final Occupancy occupancy = new Occupancy();
    private volatile Duration delay = Duration.ZERO;

    /** The listing of {@code folder} throws after {@code entriesBeforeTheFailure} entries. */
    void failListing(Path folder, int entriesBeforeTheFailure) {
        failAfter.put(folder.toAbsolutePath().normalize(), entriesBeforeTheFailure);
    }

    /**
     * The removal of {@code entry}, its copy (as a source) and its creation as a folder throw {@code failure}: an
     * {@link IOException} or an unchecked exception.
     */
    void failOperation(Path entry, Exception failure) {
        if (!(failure instanceof IOException || failure instanceof RuntimeException)) {
            throw new IllegalArgumentException("neither an IOException nor unchecked: " + failure);
        }
        failOperation.put(entry.toAbsolutePath().normalize(), failure);
    }

    /** {@code folder} cannot be opened: listing it throws {@link AccessDeniedException}. */
    void failOpening(Path folder) {
        failOpening.add(folder.toAbsolutePath().normalize());
    }

    /** Removals done so far, through this file system. */
    int deletions() {
        return deletions.get();
    }

    /**
     * Listings (from opening to closing), removals, copies and creations of folders in progress. Each of them lasts at
     * least {@link #slowDown the delay}, so that those that may overlap do.
     */
    Occupancy occupancy() {
        return occupancy;
    }

    /** Each listing, removal, copy and creation of a folder lasts at least {@code delay}. */
    void slowDown(Duration delay) {
        this.delay = delay;
    }

    private void checkOperation(Path real) throws IOException {
        Exception failure = failOperation.get(real.toAbsolutePath().normalize());
        if (failure instanceof IOException e) {
            throw e;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }

    /** Enters an operation, waits for the delay, and returns what to call when it ends. */
    private Runnable enter() {
        occupancy.enter();
        Duration wait = delay;
        if (!wait.isZero()) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return occupancy::exit;
    }

    /** The path of the real file system, as this one sees it. */
    Path wrap(Path path) {
        return new FaultyPath(this, path);
    }

    private static Path unwrap(Path path) {
        if (path instanceof FaultyPath faulty) {
            return faulty.real;
        }
        throw new ProviderMismatchException();
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return real.isReadOnly();
    }

    @Override
    public String getSeparator() {
        return real.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        List<Path> roots = new ArrayList<>();
        real.getRootDirectories().forEach(root -> roots.add(wrap(root)));
        return roots;
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return real.getFileStores();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return real.supportedFileAttributeViews();
    }

    @Override
    public Path getPath(String first, String... more) {
        return wrap(real.getPath(first, more));
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        PathMatcher matcher = real.getPathMatcher(syntaxAndPattern);
        return path -> matcher.matches(unwrap(path));
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return real.getUserPrincipalLookupService();
    }

    @Override
    public WatchService newWatchService() throws IOException {
        return real.newWatchService();
    }

    /** A path of the real file system that belongs to this one. */
    private static final class FaultyPath implements Path {

        private final FaultyFileSystem fs;
        private final Path real;

        FaultyPath(FaultyFileSystem fs, Path real) {
            this.fs = fs;
            this.real = real;
        }

        private Path wrapOrNull(Path path) {
            return path == null ? null : fs.wrap(path);
        }

        private static Path realOf(Path other) {
            return other instanceof FaultyPath faulty ? faulty.real : other;
        }

        @Override
        public FileSystem getFileSystem() {
            return fs;
        }

        @Override
        public boolean isAbsolute() {
            return real.isAbsolute();
        }

        @Override
        public Path getRoot() {
            return wrapOrNull(real.getRoot());
        }

        @Override
        public Path getFileName() {
            return wrapOrNull(real.getFileName());
        }

        @Override
        public Path getParent() {
            return wrapOrNull(real.getParent());
        }

        @Override
        public int getNameCount() {
            return real.getNameCount();
        }

        @Override
        public Path getName(int index) {
            return fs.wrap(real.getName(index));
        }

        @Override
        public Path subpath(int beginIndex, int endIndex) {
            return fs.wrap(real.subpath(beginIndex, endIndex));
        }

        @Override
        public boolean startsWith(Path other) {
            return real.startsWith(realOf(other));
        }

        @Override
        public boolean endsWith(Path other) {
            return real.endsWith(realOf(other));
        }

        @Override
        public Path normalize() {
            return fs.wrap(real.normalize());
        }

        @Override
        public Path resolve(Path other) {
            return fs.wrap(real.resolve(realOf(other)));
        }

        @Override
        public Path relativize(Path other) {
            return fs.wrap(real.relativize(realOf(other)));
        }

        @Override
        public URI toUri() {
            return real.toUri();
        }

        @Override
        public Path toAbsolutePath() {
            return fs.wrap(real.toAbsolutePath());
        }

        @Override
        public Path toRealPath(LinkOption... options) throws IOException {
            return fs.wrap(real.toRealPath(options));
        }

        @Override
        public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers)
                throws IOException {
            return real.register(watcher, events, modifiers);
        }

        @Override
        public int compareTo(Path other) {
            return real.compareTo(realOf(other));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FaultyPath faulty && real.equals(faulty.real);
        }

        @Override
        public int hashCode() {
            return real.hashCode();
        }

        @Override
        public String toString() {
            return real.toString();
        }
    }

    private final class Provider extends FileSystemProvider {

        @Override
        public String getScheme() {
            return "faulty";
        }

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path getPath(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                  FileAttribute<?>... attributes) throws IOException {
            return realProvider.newByteChannel(unwrap(path), options, attributes);
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
                throws IOException {
            Path realDir = unwrap(dir);
            if (failOpening.contains(realDir.toAbsolutePath().normalize())) {
                throw new AccessDeniedException(realDir.toString(), null, "injected failure while opening");
            }
            Runnable exit = enter();
            DirectoryStream<Path> stream;
            try {
                stream = realProvider.newDirectoryStream(realDir, entry -> filter.accept(wrap(entry)));
            } catch (IOException | RuntimeException e) {
                exit.run();
                throw e;
            }
            Integer limit = failAfter.get(realDir.toAbsolutePath().normalize());
            AtomicBoolean closed = new AtomicBoolean();
            return new DirectoryStream<>() {
                @Override
                public Iterator<Path> iterator() {
                    Iterator<Path> entries = stream.iterator();
                    return new Iterator<>() {
                        private int read;

                        @Override
                        public boolean hasNext() {
                            if (limit != null && read >= limit) {
                                throw new DirectoryIteratorException(
                                        new IOException("injected failure while listing " + realDir));
                            }
                            return entries.hasNext();
                        }

                        @Override
                        public Path next() {
                            read++;
                            return wrap(entries.next());
                        }
                    };
                }

                /** May be called more than once: the listing ends (for {@link #occupancy}) at the first call. */
                @Override
                public void close() throws IOException {
                    try {
                        stream.close();
                    } finally {
                        if (closed.compareAndSet(false, true)) {
                            exit.run();
                        }
                    }
                }
            };
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attributes) throws IOException {
            Runnable exit = enter();
            try {
                checkOperation(unwrap(dir));
                realProvider.createDirectory(unwrap(dir), attributes);
            } finally {
                exit.run();
            }
        }

        @Override
        public void delete(Path path) throws IOException {
            Path real = unwrap(path);
            Runnable exit = enter();
            try {
                checkOperation(real);
                realProvider.delete(real);
                deletions.incrementAndGet();
            } finally {
                exit.run();
            }
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            Runnable exit = enter();
            try {
                checkOperation(unwrap(source));
                realProvider.copy(unwrap(source), unwrap(target), options);
            } finally {
                exit.run();
            }
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            realProvider.move(unwrap(source), unwrap(target), options);
        }

        @Override
        public boolean isSameFile(Path a, Path b) throws IOException {
            return realProvider.isSameFile(unwrap(a), unwrap(b));
        }

        @Override
        public boolean isHidden(Path path) throws IOException {
            return realProvider.isHidden(unwrap(path));
        }

        @Override
        public FileStore getFileStore(Path path) throws IOException {
            return realProvider.getFileStore(unwrap(path));
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) throws IOException {
            realProvider.checkAccess(unwrap(path), modes);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                                                                    LinkOption... options) {
            return realProvider.getFileAttributeView(unwrap(path), type, options);
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
                throws IOException {
            return realProvider.readAttributes(unwrap(path), type, options);
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
                throws IOException {
            return realProvider.readAttributes(unwrap(path), attributes, options);
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
                throws IOException {
            realProvider.setAttribute(unwrap(path), attribute, value, options);
        }
    }
}
