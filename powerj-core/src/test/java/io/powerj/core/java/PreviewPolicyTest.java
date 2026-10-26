package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.Executable;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The fixed set of Java methods that a preview may run (FR-66): one verdict per kind of method, with an example of
 * each. The set is the safety of typing: a method that is allowed here runs on every keystroke.
 */
class PreviewPolicyTest {

    /** A class of the product's own world: not in java.base, so nothing of it is allowed. */
    public static final class Foreign {
        public Foreign() { }

        public String run() {
            return "x";
        }
    }

    private static Executable method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Executable constructor(Class<?> type, Class<?>... parameters) {
        try {
            return type.getConstructor(parameters);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean allows(Executable executable) {
        return PreviewPolicy.allows(executable);
    }

    @Test
    void methodsOfTypesThatOnlyComputeAreAllowed() {
        assertThat(allows(method(String.class, "length"))).isTrue();
        assertThat(allows(method(String.class, "valueOf", int.class))).isTrue();
        assertThat(allows(method(Math.class, "max", int.class, int.class))).isTrue();
        assertThat(allows(method(BigInteger.class, "add", BigInteger.class))).isTrue();
        assertThat(allows(method(Optional.class, "get"))).isTrue();
        assertThat(allows(method(Objects.class, "requireNonNull", Object.class))).isTrue();
        assertThat(allows(method(Pattern.class, "compile", String.class))).isTrue();
        assertThat(allows(method(Comparator.class, "reversed"))).isTrue();
        assertThat(allows(method(Number.class, "intValue"))).isTrue();
        assertThat(allows(method(Enum.class, "name"))).isTrue();
    }

    @Test
    void theDatesAndTheStreamsOfTheJdkAreAllowed() {
        assertThat(allows(method(Instant.class, "now"))).isTrue();
        assertThat(allows(method(Instant.class, "plusSeconds", long.class))).isTrue();
        assertThat(allows(method(Stream.class, "map", java.util.function.Function.class))).isTrue();
        assertThat(allows(method(java.util.function.Function.class, "apply", Object.class))).isTrue();
    }

    @Test
    void aParallelStreamIsNeverAllowed() {
        assertThat(allows(method(java.util.stream.BaseStream.class, "parallel"))).isFalse();
        assertThat(allows(method(Collection.class, "parallelStream"))).isFalse();
        assertThat(allows(method(Arrays.class, "parallelSort", int[].class))).isFalse();
    }

    @Test
    void theMethodsOfObjectAreAllowedOnAnyTypeOfTheJdk() {
        assertThat(allows(method(File.class, "toString"))).isTrue();
        assertThat(allows(method(File.class, "hashCode"))).isTrue();
        assertThat(allows(method(File.class, "equals", Object.class))).isTrue();
        assertThat(allows(method(File.class, "compareTo", File.class))).isTrue();
        assertThat(allows(method(Object.class, "getClass"))).isTrue();
        assertThat(allows(method(StringBuilder.class, "toString"))).isTrue();
    }

    @Test
    void whatChangesSomethingIsNotAllowed() {
        assertThat(allows(method(File.class, "delete"))).isFalse();
        assertThat(allows(method(StringBuilder.class, "append", String.class))).isFalse();
        assertThat(allows(method(Thread.class, "sleep", long.class))).isFalse();
        assertThat(allows(method(System.class, "exit", int.class))).isFalse();
        assertThat(allows(method(System.class, "getProperty", String.class))).isFalse();
        assertThat(allows(method(Runtime.class, "exec", String.class))).isFalse();
        assertThat(allows(method(Process.class, "destroy"))).isFalse();
        assertThat(allows(method(Object.class, "wait"))).isFalse();
    }

    @Test
    void aMethodOfAnotherModuleIsNeverAllowed() {
        assertThat(allows(method(Foreign.class, "run"))).isFalse();
        assertThat(allows(method(io.powerj.api.Row.class, "of", Map.class))).isFalse();
    }

    @Test
    void onlyAFewConstructorsAreAllowed() {
        assertThat(allows(constructor(String.class, String.class))).isTrue();
        assertThat(allows(constructor(ArrayList.class))).isTrue();
        assertThat(allows(constructor(HashMap.class))).isTrue();
        assertThat(allows(constructor(Object.class))).isTrue();
        assertThat(allows(constructor(BigInteger.class, String.class))).isTrue();
        assertThat(allows(constructor(java.io.FileWriter.class, String.class))).isFalse();
        assertThat(allows(constructor(Thread.class))).isFalse();
        assertThat(allows(constructor(StringBuilder.class))).isFalse();
        assertThat(allows(constructor(Foreign.class))).isFalse();
    }

    @Test
    void aPathOnlyComputesPathsExceptWatching() {
        assertThat(allows(method(Path.class, "resolve", String.class))).isTrue();
        assertThat(allows(method(Path.class, "of", String.class, String[].class))).isTrue();
        assertThat(allows(method(Path.class, "register", WatchService.class, WatchEvent.Kind[].class))).isFalse();
    }

    @Test
    void filesAreOnlyRead() {
        assertThat(allows(method(Files.class, "exists", Path.class, java.nio.file.LinkOption[].class))).isTrue();
        assertThat(allows(method(Files.class, "readString", Path.class))).isTrue();
        assertThat(allows(method(Files.class, "list", Path.class))).isTrue();
        assertThat(allows(method(Files.class, "delete", Path.class))).isFalse();
        assertThat(allows(method(Files.class, "writeString", Path.class, CharSequence.class,
                java.nio.file.OpenOption[].class))).isFalse();
        assertThat(allows(method(Files.class, "createFile", Path.class,
                java.nio.file.attribute.FileAttribute[].class))).isFalse();
    }

    @Test
    void arraysAndCollectionsHelpersAreAllowedWhenTheyDoNotMutate() {
        assertThat(allows(method(Arrays.class, "asList", Object[].class))).isTrue();
        assertThat(allows(method(Arrays.class, "toString", int[].class))).isTrue();
        assertThat(allows(method(Arrays.class, "sort", int[].class))).isFalse();
        assertThat(allows(method(Arrays.class, "fill", int[].class, int.class))).isFalse();
        assertThat(allows(method(Collections.class, "emptyList"))).isTrue();
        assertThat(allows(method(Collections.class, "max", Collection.class))).isTrue();
        assertThat(allows(method(Collections.class, "shuffle", List.class))).isFalse();
        assertThat(allows(method(Collections.class, "sort", List.class))).isFalse();
    }

    @Test
    void aClassIsOnlyAskedItsName() {
        assertThat(allows(method(Class.class, "getName"))).isTrue();
        assertThat(allows(method(Class.class, "isInstance", Object.class))).isTrue();
        assertThat(allows(method(Class.class, "forName", String.class))).isFalse();
        assertThat(allows(method(Class.class, "getMethod", String.class, Class[].class))).isFalse();
        assertThat(allows(method(Class.class, "getDeclaredFields"))).isFalse();
    }

    @Test
    void collectionsMapsAndIteratorsAreOnlyRead() {
        assertThat(allows(method(List.class, "size"))).isTrue();
        assertThat(allows(method(List.class, "get", int.class))).isTrue();
        assertThat(allows(method(Collection.class, "stream"))).isTrue();
        assertThat(allows(method(Map.class, "get", Object.class))).isTrue();
        assertThat(allows(method(Map.class, "getOrDefault", Object.class, Object.class))).isTrue();
        assertThat(allows(method(Map.Entry.class, "getValue"))).isTrue();
        assertThat(allows(method(Iterator.class, "next"))).isTrue();
        assertThat(allows(method(ArrayList.class, "size"))).isTrue();
        assertThat(allows(method(List.class, "add", Object.class))).isFalse();
        assertThat(allows(method(Map.class, "put", Object.class, Object.class))).isFalse();
        assertThat(allows(method(Iterator.class, "remove"))).isFalse();
        assertThat(allows(method(List.class, "sort", Comparator.class))).isFalse();
        assertThat(allows(method(Collection.class, "clear"))).isFalse();
    }

    @Test
    void theNameOfAMethodAloneAllowsNothingOutsideTheCollections() {
        // "length", "get", "next" are reading methods of a collection: a File or an AtomicInteger that has them is not one.
        assertThat(allows(method(File.class, "length"))).isFalse();
        assertThat(allows(method(java.util.concurrent.atomic.AtomicInteger.class, "incrementAndGet"))).isFalse();
        assertThat(allows(method(java.util.concurrent.atomic.AtomicInteger.class, "get"))).isFalse();
    }
}
