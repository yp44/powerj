package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cmdlets, subexpressions, help and which, with a test cmdlet. */
class CmdletInterpreterTest {

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

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void cmdletOutputIsATable() throws Exception {
        assertThat(run("items -n 2")).isEqualTo("""
                name   size
                ----   ----
                item1    10
                item2    20
                """);
    }

    @Test
    void assignmentKeepsTheObjects() throws Exception {
        run("$i = items");
        assertThat(session.variable("i")).isEqualTo(List.of(
                new FakeCmdlets.Item("item1", 10), new FakeCmdlets.Item("item2", 20), new FakeCmdlets.Item("item3", 30)));
        assertThat(run("$i[1].size")).isEqualTo("20\n");
        assertThat(run("$i*.name")).isEqualTo("item1\nitem2\nitem3\n");
    }

    @Test
    void subExpressions() throws Exception {
        assertThat(run("(items -n 2)*.name")).isEqualTo("item1\nitem2\n");
        assertThat(run("(items)[-1].size")).isEqualTo("30\n");
        assertThat(run("(pwd)")).isEqualTo(tmp + "\n");
        assertThat(run("\"nom : $last\"")).isNotEmpty(); // no syntax error
    }

    @Test
    void unknownPropertyListsTheKnownOnes() throws Exception {
        run("(items)[0].siz");
        assertThat(errors).containsExactly("Item n'a pas de propriété 'siz' (propriétés : name, size)");
    }

    @Test
    void optionErrorsAndHelp() throws Exception {
        run("items --cont 2");
        assertThat(errors).containsExactly("items : option inconnue --cont, vouliez-vous dire --count ?");
        assertThat(run("items --help")).contains("items — Produit des objets de test", "-n, --count", "Sortie : Item (name, size)");
        assertThat(run("help items")).contains("Usage : items [options]");
        assertThat(run("help")).contains("Commandes internes", "Test", "items", "Produit des objets de test");
    }

    @Test
    void nonBlockingErrorsAndOnError() throws Exception {
        assertThat(run("items --fail")).contains("item3");
        assertThat(errors).containsExactly("items : problème sur item1");
        assertThat(session.lastSucceeded()).isFalse();

        run("items --fail --on-error silent");
        assertThat(errors).isEmpty();

        assertThat(run("items --fail --on-error stop")).doesNotContain("item3");
        assertThat(errors).containsExactly("items : problème sur item1");
    }

    @Test
    void whichAndHelpMembers() throws Exception {
        assertThat(run("which items cd")).contains("items → cmdlet (", "cd → commande interne");
        assertThat(run("help members (items)[0]")).contains("name", "propriété", "size", "méthode", "int")
                .doesNotContain("hashCode", "equals");
        run("which ^absent");
        assertThat(errors).containsExactly("which : programme introuvable : absent");
    }

    @Test
    void redirectingACmdletWritesTheTableToAFile() throws Exception {
        run("items -n 1 > items.txt");
        assertThat(Files.readString(tmp.resolve("items.txt"))).contains("name", "item1");
    }

    @Test
    void pathPropertiesThroughPublicInterfaces() throws Exception {
        Files.createDirectories(tmp.resolve("a/b"));
        run("$p = (pwd)");
        assertThat(run("$p.fileName")).isEqualTo(tmp.getFileName() + "\n");
        assertThat(run("$p.parent")).isEqualTo(tmp.getParent() + "\n");
        assertThat(run("$p.absolute")).isEqualTo("true\n");
    }

    @Test
    void spreadAppliesToEachElement() throws Exception {
        run("$i = items");
        assertThat(run("$i.size()")).isEqualTo("3\n");
        assertThat(run("$i*.size")).isEqualTo("10\n20\n30\n");
        assertThat(run("$i*.name*.toUpperCase()")).isEqualTo("ITEM1\nITEM2\nITEM3\n");
        assertThat(run("$i*.name.size()")).isEqualTo("3\n");
        assertThat(run("$i*.name.get(1).length()")).isEqualTo("5\n");
        // A single value counts as one element, null as none.
        assertThat(run("(items -n 1)*.name")).isEqualTo("item1\n");
        run("$n = null");
        assertThat(run("$n*.name.size()")).isEqualTo("0\n");
        // Without *., the property applies to the list itself: explicit error.
        run("$i.size");
        assertThat(errors).singleElement().asString()
                .contains("List n'a pas de propriété 'size'", "pour chaque élément : *.size", "méthode : size()");
        run("$i.name");
        assertThat(errors).singleElement().asString().contains("pour chaque élément : *.name");
        // *. is not a multiplication.
        assertThat(run("$x = 2 * 3; $x")).isEqualTo("6\n");
    }
}
