package io.powerj.core.java;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.powerj.core.java.PreviewRule.Verdict;

/**
 * Fixed set of the Java methods and constructors allowed in preview mode (FR-66): those that only compute a value
 * and change nothing outside it. Built into PowerJ, not configurable. Everything else stops the preview.
 * <p>
 * Only JDK classes ({@code java.base}) are concerned: a method of a third-party module is never allowed. The
 * components of a record are always read ({@code PropertyAccess}).
 */
public final class PreviewPolicy {

    /** Immutable types, or types whose methods only compute: every public method is allowed. */
    private static final Set<Class<?>> PURE_TYPES = Set.of(
            String.class, CharSequence.class, Character.class, Boolean.class, Number.class, Byte.class,
            Short.class, Integer.class, Long.class, Float.class, Double.class, Math.class, StrictMath.class,
            java.math.BigInteger.class, java.math.BigDecimal.class, Enum.class, Record.class, Comparable.class,
            java.util.Optional.class, java.util.OptionalInt.class, java.util.OptionalLong.class,
            java.util.OptionalDouble.class, java.util.Comparator.class, java.util.Objects.class,
            java.util.regex.Pattern.class, java.util.regex.Matcher.class, java.util.regex.MatchResult.class,
            java.nio.file.Paths.class);

    /** Packages whose types are immutable or only build computations (streams, functions, dates). */
    private static final Set<String> PURE_PACKAGES = Set.of(
            "java.time", "java.time.format", "java.time.temporal", "java.time.chrono",
            "java.util.stream", "java.util.function");

    /**
     * Never allowed, even in an allowed type: a parallel stream calls the lambdas in other threads, where a block
     * converted before the preview would no longer be checked.
     */
    private static final Set<String> PARALLEL = Set.of("parallel", "parallelStream", "parallelSort", "parallelSetAll",
            "parallelPrefix");

    /** Methods of {@link Object} and comparison, allowed on any JDK type. */
    private static final Set<String> OBJECT_METHODS = Set.of("toString", "equals", "hashCode", "getClass", "compareTo");

    /** Reading methods of collections, maps and iterators; their mutators ({@code add}, {@code sort}…) stay out. */
    private static final Set<String> READ_COLLECTION = Set.of(
            "size", "isEmpty", "contains", "containsAll", "get", "getFirst", "getLast", "indexOf", "lastIndexOf",
            "subList", "stream", "toArray", "forEach", "iterator", "listIterator", "spliterator",
            "reversed", "keySet", "values", "entrySet", "sequencedKeySet", "sequencedValues", "sequencedEntrySet",
            "containsKey", "containsValue", "getOrDefault", "getKey", "getValue", "first", "last", "firstKey",
            "lastKey", "firstEntry", "lastEntry", "headSet", "tailSet", "subSet", "headMap", "tailMap", "subMap",
            "comparator", "hasNext", "next", "hasPrevious", "previous", "nextIndex", "previousIndex", "peek",
            "peekFirst", "peekLast", "element", "descendingIterator", "descendingSet", "descendingMap",
            "lower", "higher", "floor", "ceiling", "lowerKey", "higherKey", "floorKey", "ceilingKey",
            "of", "copyOf", "ofEntries", "entry", "comparingByKey", "comparingByValue");

    /** Types whose reading methods ({@link #READ_COLLECTION}) are allowed, implementations included. */
    private static final Set<Class<?>> COLLECTION_TYPES = Set.of(Iterable.class, Collection.class, Map.class,
            Map.Entry.class, Iterator.class);

    private static final Set<String> ARRAYS = Set.of("asList", "stream", "toString", "deepToString", "equals",
            "deepEquals", "hashCode", "deepHashCode", "binarySearch", "copyOf", "copyOfRange", "compare", "mismatch");

    private static final Set<String> COLLECTIONS = Set.of("emptyList", "emptySet", "emptyMap", "emptySortedSet",
            "emptySortedMap", "emptyNavigableSet", "emptyNavigableMap", "emptyIterator", "singletonList", "singleton",
            "singletonMap", "unmodifiableList", "unmodifiableSet", "unmodifiableMap", "unmodifiableCollection",
            "unmodifiableSortedSet", "unmodifiableSortedMap", "unmodifiableNavigableSet", "unmodifiableNavigableMap",
            "unmodifiableSequencedCollection", "unmodifiableSequencedSet", "unmodifiableSequencedMap", "nCopies",
            "max", "min", "frequency", "disjoint", "reverseOrder");

    /** {@code Files}: looking at the file system, which costs nothing whatever the path. */
    private static final Set<String> FILE_ATTRIBUTES = Set.of("exists", "notExists", "isDirectory", "isRegularFile",
            "isSymbolicLink", "isReadable", "isWritable", "isExecutable", "isHidden", "isSameFile", "size",
            "getLastModifiedTime", "getOwner", "readAttributes", "getAttribute", "probeContentType");

    /**
     * {@code Files}: reading the content of a file or listing a folder. Allowed on the name alone, whatever the path,
     * so what is read, and for how long, depends on what is typed: {@code Files.readAllBytes(Path.of("/dev/zero"))}
     * can exhaust the memory and a FIFO blocks until the deadline. The preview warns when the line uses one
     * ({@link #readsFiles}); a rule on the argument is not made (#81, FR-66).
     */
    private static final Set<String> FILE_READS = Set.of("readAllLines", "readString", "readAllBytes", "lines", "list",
            "walk", "find");

    /** {@code Files}: reading the file system only. */
    private static final Set<String> FILES = Stream.concat(FILE_ATTRIBUTES.stream(), FILE_READS.stream())
            .collect(Collectors.toUnmodifiableSet());

    /** {@code Class}: names and type tests, not reflection. */
    private static final Set<String> CLASS = Set.of("getName", "getSimpleName", "getTypeName", "getPackageName",
            "getCanonicalName", "isInstance", "isRecord", "isEnum", "isArray", "isInterface", "isPrimitive",
            "getSuperclass", "getInterfaces", "getComponentType", "isAssignableFrom");

    /** Constructors allowed: values and empty collections, which a preview cannot keep (no assignment). */
    private static final Set<Class<?>> CONSTRUCTIBLE = Set.of(String.class, java.math.BigInteger.class,
            java.math.BigDecimal.class, java.util.ArrayList.class, java.util.LinkedList.class, java.util.HashMap.class,
            java.util.LinkedHashMap.class, java.util.TreeMap.class, java.util.HashSet.class,
            java.util.LinkedHashSet.class, java.util.TreeSet.class, java.util.ArrayDeque.class, Object.class);

    /**
     * The rules in the order they are read. A refusal comes before an allowance that it must overrule (a parallel
     * stream is in an allowed package); the last rule is the only one that lets a method through on its name alone.
     */
    private static final List<PreviewRule> STANDARD_RULES = List.of(
            // Only the JDK: a method of a third-party module is never allowed.
            PreviewRule.when(e -> !"java.base".equals(e.getDeclaringClass().getModule().getName()), Verdict.REFUSE),
            // A constructor is allowed for a value or an empty collection, and refused for anything else.
            PreviewRule.constructorsOf(CONSTRUCTIBLE),
            PreviewRule.when(e -> PARALLEL.contains(e.getName()), Verdict.REFUSE),
            PreviewRule.when(e -> OBJECT_METHODS.contains(e.getName())
                    || PURE_TYPES.contains(e.getDeclaringClass())
                    || PURE_PACKAGES.contains(e.getDeclaringClass().getPackageName()), Verdict.ALLOW),
            // A Path only computes paths; register() starts watching a folder.
            PreviewRule.decidesFor(java.nio.file.Path.class, name -> !name.equals("register")),
            PreviewRule.decidesFor(java.nio.file.Files.class, FILES::contains),
            PreviewRule.decidesFor(java.util.Arrays.class, ARRAYS::contains),
            PreviewRule.decidesFor(java.util.Collections.class, COLLECTIONS::contains),
            PreviewRule.decidesFor(Class.class, CLASS::contains),
            PreviewRule.when(e -> READ_COLLECTION.contains(e.getName())
                    && COLLECTION_TYPES.stream().anyMatch(t -> t.isAssignableFrom(e.getDeclaringClass())), Verdict.ALLOW));

    private static final PreviewPolicy STANDARD = new PreviewPolicy(STANDARD_RULES);

    private final List<PreviewRule> rules;

    PreviewPolicy(List<PreviewRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** {@code true} if this method or constructor may run in preview mode. */
    public static boolean allows(Executable executable) {
        return STANDARD.permits(executable);
    }

    /**
     * {@code true} for the {@code Files} methods that read a file or list a folder on the name alone (#81). The preview
     * tells the user when a line it runs while the user types used one.
     */
    public static boolean readsFiles(Executable executable) {
        return executable.getDeclaringClass() == java.nio.file.Files.class && FILE_READS.contains(executable.getName());
    }

    /** What the first rule that has an opinion says; a method that no rule allows is refused. */
    boolean permits(Executable executable) {
        for (PreviewRule rule : rules) {
            switch (rule.judge(executable)) {
                case ALLOW -> {
                    return true;
                }
                case REFUSE -> {
                    return false;
                }
                case ABSTAIN -> { }
            }
        }
        return false;
    }

    /** {@code Files.delete}, {@code new FileWriter}: name shown in the reason of a stopped preview. */
    static String describe(Executable executable) {
        String owner = executable.getDeclaringClass().getSimpleName();
        return executable instanceof Method method ? owner + "." + method.getName() : "new " + owner;
    }
}
