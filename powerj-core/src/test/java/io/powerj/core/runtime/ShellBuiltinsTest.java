package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.command.ModuleLoader;
import io.powerj.core.error.PjException;
import io.powerj.core.nativeexec.CommandResolver;
import io.powerj.core.support.FakeCmdlets;

/** {@code which}, {@code import}, {@code mod-load} and {@code mod-list}, built from the parts of the interpreter. */
class ShellBuiltinsTest {

    @TempDir
    Path tmp;

    private final List<String> errors = new ArrayList<>();
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();
    private Session session;
    private CmdletRegistry registry;
    private ModuleLoader modules;
    private ShellIo io;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, Map.of());
        io = new ShellIo(new PrintWriter(new StringWriter(), true), errors::add, false);
        registry = CmdletRegistry.of(FakeCmdlets.all());
        modules = new ModuleLoader(registry, Set.of());
        builtins.put("cd", Builtins::cd);
    }

    private Builtin which() {
        return Builtins.which(() -> new CommandResolution(builtins, registry, new CommandResolver()));
    }

    @Test
    void whichNeedsAName() {
        assertThatThrownBy(() -> which().run(List.of(), session)).isInstanceOf(PjException.class)
                .hasMessage("which: command name expected");
    }

    @Test
    void whichSaysWhatEachNameStandsFor() throws Exception {
        List<Object> lines = which().run(List.of("cd", "items"), session);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).toString()).contains("cd").contains("built-in");
        assertThat(lines.get(1).toString()).contains("items").contains("cmdlet");
    }

    @Test
    void whichFailsOnAnUnknownNameAndOnAnUnknownProgram() {
        assertThatThrownBy(() -> which().run(List.of("nothing-of-that-name"), session)).isInstanceOf(PjException.class)
                .hasMessageContaining("nothing-of-that-name");
        assertThatThrownBy(() -> which().run(List.of("^items"), session)).isInstanceOf(PjException.class)
                .hasMessageContaining("items");
    }

    @Test
    void importWithoutArgumentsListsTheImportsAndWithOnesAddsThem() {
        int before = Builtins.importClasses(List.of(), session).size();

        assertThat(Builtins.importClasses(List.of("java.security.*"), session)).isEmpty();

        assertThat(Builtins.importClasses(List.of(), session)).hasSize(before + 1).contains("java.security.*");
    }

    @Test
    void modListTakesNoArgumentAndListsTheModules() throws Exception {
        assertThatThrownBy(() -> Builtins.modList(registry).run(List.of("x"), session)).isInstanceOf(PjException.class)
                .hasMessage("mod-list: no arguments expected");

        assertThat(Builtins.modList(registry).run(List.of(), session)).isEqualTo(List.copyOf(registry.modules()));
    }

    @Test
    void modLoadNeedsAPath() {
        assertThatThrownBy(() -> Builtins.modLoad(modules, io).run(List.of(), session)).isInstanceOf(PjException.class)
                .hasMessage("mod-load: path of a module (.jar or directory) expected");
    }

    @Test
    void modLoadReportsAModuleThatCannotBeLoaded() throws Exception {
        Files.writeString(tmp.resolve("notes.txt"), "x");

        assertThatThrownBy(() -> Builtins.modLoad(modules, io).run(List.of("notes.txt"), session))
                .isInstanceOf(PjException.class).hasMessage("mod-load: notes.txt not loaded");
        assertThat(errors).isNotEmpty(); // the reason, before the summary
    }

    @Test
    void modLoadResolvesThePathAgainstTheCurrentDirectory() {
        Path sub = tmp.resolve("sub");
        try {
            Files.createDirectory(sub);
            Files.writeString(sub.resolve("notes.txt"), "x");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        session.changeDirectory(sub);

        assertThatThrownBy(() -> Builtins.modLoad(modules, io).run(List.of("notes.txt"), session))
                .hasMessage("mod-load: notes.txt not loaded"); // found (and refused), not "no such file"
        assertThat(errors.getFirst()).contains("a module is a .jar file");
    }
}
