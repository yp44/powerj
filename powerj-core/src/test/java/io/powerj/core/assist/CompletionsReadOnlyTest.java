package io.powerj.core.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/**
 * Completion promises to run no method (review C-23): the type of {@code $x.property.} comes from the signature,
 * and from the real value only where reading it cannot do anything.
 */
class CompletionsReadOnlyTest {

    /** Counts the getters that run: a lazy getter that opens a resource, for instance. */
    public static final AtomicInteger GETTER_CALLS = new AtomicInteger();

    public static final class Lazy {
        public Object getHandle() {
            GETTER_CALLS.incrementAndGet();
            return "opened";
        }

        public boolean isOpen() {
            GETTER_CALLS.incrementAndGet();
            return true;
        }

        public Object field = "a text";
    }

    public record Holder(Object payload) { }

    @TempDir
    Path tmp;

    private Session session;
    private Completions completions;

    @BeforeEach
    void setUp() {
        GETTER_CALLS.set(0);
        session = new Session(tmp, tmp, Map.of("PATH", ""));
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of()));
        completions = new Completions(interpreter);
    }

    private List<String> after(String line) {
        return completions.complete(line, line.length()).candidates().stream()
                .map(Completions.Candidate::value).toList();
    }

    @Test
    void aGetterOfTheUserTypeIsNeverCalled() {
        session.setVariable("lazy", new Lazy());

        var members = after("$lazy.handle.");
        after("$lazy.open.");

        assertThat(GETTER_CALLS).hasValue(0);
        assertThat(members).contains("toString()", "getClass()") // typed by the signature: Object
                .doesNotContain("length()");                      // not by what the getter would return: a String
    }

    @Test
    void theGetterIsStillOfferedAsAProperty() {
        session.setVariable("lazy", new Lazy());

        assertThat(after("$lazy.")).contains("handle", "open", "field");
        assertThat(GETTER_CALLS).hasValue(0);
    }

    @Test
    void theComponentOfARecordIsTypedByItsRealValue() {
        session.setVariable("holder", new Holder("text"));

        assertThat(after("$holder.payload.")).contains("length()", "isBlank()");
    }

    @Test
    void aPublicFieldIsTypedByItsRealValue() {
        session.setVariable("lazy", new Lazy());

        assertThat(after("$lazy.field.")).contains("length()");
    }

    @Test
    void aGetterThatTheSafeListAllowsIsTypedByItsRealValue() {
        session.setVariable("entry", Map.entry("key", "text"));

        assertThat(after("$entry.value.")).contains("length()", "isBlank()");
    }

    /** A map of the user: its {@code get} is a method like any other. */
    public static final class LazyMap extends java.util.AbstractMap<String, Object> {
        @Override
        public Object get(Object key) {
            GETTER_CALLS.incrementAndGet();
            return "computed";
        }

        @Override
        public java.util.Set<Entry<String, Object>> entrySet() {
            return java.util.Set.of();
        }
    }

    @Test
    void theGetOfAMapOfTheUserIsNeverCalled() {
        session.setVariable("lazyMap", new LazyMap());

        var members = after("$lazyMap.key.");

        assertThat(GETTER_CALLS).hasValue(0);
        assertThat(members).doesNotContain("length()");
    }

    @Test
    void aMapKeyIsTypedByItsRealValue() {
        session.setVariable("map", Map.of("name", "text"));

        assertThat(after("$map.name.")).contains("length()");
    }
}
