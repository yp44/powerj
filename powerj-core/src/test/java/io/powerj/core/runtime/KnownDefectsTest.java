package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.assist.Completions;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.support.FakeCmdlets;

/**
 * Defects of the architecture review that a test demonstrates (docs/REVUE-ARCHITECTURE.md, §4). A test marked
 * {@code @Disabled} is red today: it states the expected behaviour and names the phase of the plan that fixes it, where
 * the annotation is removed. See also {@code KnownDefectsTest} in powerj-cmdlets and {@code ExitGuardTest} in
 * powerj-architecture.
 */
class KnownDefectsTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, Map.of("PATH", ""));
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(FakeCmdlets.all()));
    }

    /** An object whose getters do something, like {@code Process.getInputStream()} or a lazy loader. */
    public static final class Lazy {

        final AtomicInteger calls = new AtomicInteger();

        public String getName() {
            calls.incrementAndGet();
            return "name";
        }
    }

    // --- C-23: Tab completion must not call the getters of the real values of the variables (fixed in Phase 3.3) ---

    @Test
    void completionCallsNoGetter() {
        var lazy = new Lazy();
        session.setVariable("lazy", lazy);

        new Completions(interpreter).complete("$lazy.name.", "$lazy.name.".length());

        assertThat(lazy.calls).as("getters called by the completion").hasValue(0);
    }

    // --- C-07: the elements of a stream are taken without closing it ---

    @Test
    void starDotClosesTheStreamItUnrolls() throws Exception {
        var closed = new AtomicInteger();
        session.setVariable("s", Stream.of("a", "bb").onClose(closed::incrementAndGet));

        interpreter.execute("$s*.length()");

        assertThat(closed).as("close() calls").hasValue(1);
    }
}
