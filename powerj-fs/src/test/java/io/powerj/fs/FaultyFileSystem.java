package io.powerj.fs;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The file system of the machine seen through a wrapper that can fail in the middle of the listing of a folder, as a
 * flaky network file system does: the entries already read are returned, then the iteration of the folder throws
 * {@link DirectoryIteratorException}, which no real folder can be made to do on every system. Everything else goes to the
 * real file system.
 * <pre>
 * var faulty = new FaultyFileSystem();
 * faulty.failListing(folder, 2);          // the third entry of folder cannot be read
 * Path root = faulty.wrap(tmp);           // walk root, not tmp
 * </pre>
 */
final class FaultyFileSystem extends FileSystem {

    private final FileSystem real = FileSystems.getDefault();
    private final FileSystemProvider realProvider = real.provider();
    private final Provider provider = new Provider();
    /** Entries read before the failure, by folder (absolute, normalized, as the real file system sees it). */
    private final Map<Path, Integer> failAfter = new ConcurrentHashMap<>();

    /** The listing of {@code folder} throws after {@code entriesBeforeTheFailure} entries. */
    void failListing(Path folder, int entriesBeforeTheFailure) {
        failAfter.put(folder.toAbsolutePath().normalize(), entriesBeforeTheFailure);
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
            DirectoryStream<Path> stream = realProvider.newDirectoryStream(realDir,
                    entry -> filter.accept(wrap(entry)));
            Integer limit = failAfter.get(realDir.toAbsolutePath().normalize());
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

                @Override
                public void close() throws IOException {
                    stream.close();
                }
            };
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attributes) throws IOException {
            realProvider.createDirectory(unwrap(dir), attributes);
        }

        @Override
        public void delete(Path path) throws IOException {
            realProvider.delete(unwrap(path));
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            realProvider.copy(unwrap(source), unwrap(target), options);
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
