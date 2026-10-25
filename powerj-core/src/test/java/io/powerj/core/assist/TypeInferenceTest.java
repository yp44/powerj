package io.powerj.core.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Row;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.CommandCatalog;
import io.powerj.core.runtime.CommandKind;
import io.powerj.core.runtime.Resolved;
import io.powerj.core.runtime.Session;

/**
 * What an expression denotes, without running it. The catalog is a small fake: the inference needs no interpreter.
 */
class TypeInferenceTest {

    record Item(String name, Object payload) { }

    @CmdletInfo(name = "items", summary = "Emits items")
    static final class Items implements Cmdlet<Items.Params, Void, Item> {
        record Params() { }
    }

    @CmdletInfo(name = "keep", summary = "Lets the items through", passThrough = true)
    static final class Keep implements Cmdlet<Keep.Params, Item, Object> {
        record Params() { }
    }

    @CmdletInfo(name = "pick", summary = "Chooses columns")
    static final class Pick implements Cmdlet<Pick.Params, Object, Row> {
        record Params() { }

        @Override
        public List<String> columns(String arguments) {
            return List.of(arguments.strip().split("\\s+"));
        }
    }

    private static final class Catalog implements CommandCatalog {

        private final Session session;
        private final CmdletRegistry registry = CmdletRegistry.of(List.of(new Items(), new Keep(), new Pick()));

        Catalog(Session session) {
            this.session = session;
        }

        @Override
        public Session session() {
            return session;
        }

        @Override
        public Set<String> builtinNames() {
            return Set.of();
        }

        @Override
        public Collection<CmdletRegistry.Registered> cmdlets() {
            return registry.all();
        }

        @Override
        public Optional<CmdletRegistry.Registered> cmdlet(String name) {
            return registry.find(name);
        }

        @Override
        public Resolved resolve(String name) {
            if (name.equals("tool")) {
                return new Resolved.NativeCall(Path.of("/usr/bin/tool"));
            }
            return registry.find(name).<Resolved>map(Resolved.CmdletCall::new)
                    .orElseGet(() -> new Resolved.Unknown(name, false));
        }

        @Override
        public CommandKind commandKind(String name) {
            return registry.find(name).isPresent() ? CommandKind.CMDLET : CommandKind.UNKNOWN;
        }
    }

    @TempDir
    Path tmp;

    private Session session;
    private TypeInference types;
    private final Scope noScope = new Scope(null, null, Map.of());

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, Map.of("PATH", ""));
        types = new TypeInference(new Catalog(session));
    }

    private TypeInfo of(String receiver) {
        return types.typeOf(receiver, noScope);
    }

    /** Types known at the end of the line, as the analysis of the line finds the frame there. */
    private Scope scopeAt(String line) {
        return types.scope(line, LineContext.of(line, line.length()).frame());
    }

    @Test
    void aVariableIsTypedByItsValue() {
        session.setVariable("text", "abc");

        assertThat(of("$text")).isEqualTo(new TypeInfo.Instance(String.class, "abc"));
    }

    @Test
    void anUnknownVariableAndAnEmptyReceiverAreUnknown() {
        assertThat(of("$nothing")).isInstanceOf(TypeInfo.Unknown.class);
        assertThat(of("  ")).isInstanceOf(TypeInfo.Unknown.class);
        assertThat(of("((")).isInstanceOf(TypeInfo.Unknown.class);
    }

    @Test
    void literalsAndNow() {
        assertThat(of("\"abc\"")).isEqualTo(TypeInfo.instance(String.class));
        assertThat(of("42")).isEqualTo(new TypeInfo.Instance(Integer.class, 42));
        assertThat(of("now")).isEqualTo(TypeInfo.instance(Instant.class));
        assertThat(of("[1, 2]")).isEqualTo(TypeInfo.instance(List.class));
    }

    @Test
    void aClassAndAPackageAreNotValues() {
        assertThat(of("Math")).isEqualTo(new TypeInfo.Static(Math.class));
        assertThat(of("java.util")).isEqualTo(new TypeInfo.Package("java.util"));
        assertThat(of("java.util.List")).isEqualTo(new TypeInfo.Static(List.class));
    }

    @Test
    void aStaticFieldHasTheTypeOfTheField() {
        assertThat(of("Math.PI")).isEqualTo(TypeInfo.instance(double.class));
    }

    @Test
    void aMethodHasItsReturnType() {
        session.setVariable("text", "abc");

        assertThat(of("$text.length()")).isEqualTo(TypeInfo.instance(int.class));
        assertThat(of("$text.nothingLikeThis()")).isInstanceOf(TypeInfo.Unknown.class);
        assertThat(of("Math.max(1, 2)")).isInstanceOf(TypeInfo.Instance.class);
    }

    @Test
    void aVoidMethodHasNoType() {
        session.setVariable("list", new java.util.ArrayList<>(List.of("a")));

        assertThat(of("$list.clear()")).isInstanceOf(TypeInfo.Unknown.class);
    }

    @Test
    void theOutputOfAPipelineInParenthesesIsAListOfTheOutputOfItsLastCmdlet() {
        assertThat(of("(items)")).isEqualTo(new TypeInfo.ListOf(Item.class));
        assertThat(of("(^tool)")).isEqualTo(new TypeInfo.ListOf(String.class));
        assertThat(of("(nosuch)")).isInstanceOf(TypeInfo.Unknown.class);
    }

    @Test
    void anIndexAndAStarDotLookAtTheElement() {
        session.setVariable("names", List.of("a", "bb"));

        assertThat(of("$names[1]")).isEqualTo(new TypeInfo.Instance(String.class, "bb"));
        assertThat(of("$names[9]")).isEqualTo(new TypeInfo.Instance(String.class, "a"));
        session.setVariable("things", List.of(new Item("n", "p")));
        assertThat(of("$things*.name")).isEqualTo(new TypeInfo.ListOf(String.class)); // the first element answers
        assertThat(of("$things*.nothing")).isInstanceOf(TypeInfo.Unknown.class);
        assertThat(of("(items)[0]")).isEqualTo(TypeInfo.instance(Item.class));
    }

    @Test
    void anArrayHasItsComponentType() {
        session.setVariable("bytes", new byte[] {1});

        assertThat(of("$bytes[0]")).isEqualTo(TypeInfo.instance(byte.class));
    }

    @Test
    void aRecordComponentIsTypedByItsRealValueAndByItsTypeWhenTheValueIsMissing() {
        session.setVariable("item", new Item("n", "text"));
        session.setVariable("hollow", new Item("n", null));

        assertThat(of("$item.payload")).isEqualTo(new TypeInfo.Instance(String.class, "text"));
        assertThat(of("$hollow.payload")).isEqualTo(TypeInfo.instance(Object.class));
        assertThat(of("$item.nothing")).isInstanceOf(TypeInfo.Unknown.class);
    }

    @Test
    void newAndCastNameTheirClass() {
        assertThat(of("new java.io.File(\"x\")")).isEqualTo(TypeInfo.instance(java.io.File.class));
        assertThat(of("new Nothing()")).isInstanceOf(TypeInfo.Unknown.class);
    }

    @Test
    void theCurrentObjectIsWhatTheCmdletBeforeSends() {
        var scope = scopeAt("items | keep | map { $_.");

        assertThat(scope.current()).isEqualTo(Item.class);
        assertThat(types.typeOf("$_", scope)).isEqualTo(TypeInfo.instance(Item.class));
    }

    @Test
    void theColumnsOfAChoiceBecomeTheSampleRow() {
        var scope = scopeAt("items | pick a b | map { $_.");

        assertThat(scope.current()).isEqualTo(Row.class);
        assertThat(scope.sample()).isNotNull();
        assertThat(scope.sample().keySet()).containsExactly("a", "b");
    }

    @Test
    void aLambdaParameterTakesTheTypeOfTheCurrentObject() {
        var scope = scopeAt("items | map { x -> x.");

        assertThat(scope.parameters()).containsEntry("x", Item.class);
        assertThat(types.typeOf("x", scope)).isEqualTo(TypeInfo.instance(Item.class));
    }

    @Test
    void aProgramSendsText() {
        assertThat(scopeAt("^tool | map { $_.").current()).isEqualTo(String.class); // forced
        assertThat(scopeAt("tool | map { $_.").current()).isEqualTo(String.class);  // found
    }

    @Test
    void nothingIsKnownAfterACommandThatSaysNothingOfItsOutput() {
        assertThat(scopeAt("nosuch | map { $_.").current()).isNull();
        assertThat(scopeAt("map { $_.").current()).isNull();
    }
}
