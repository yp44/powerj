package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;

/** {@code ls} et {@code env} découverts par ServiceLoader, comme dans le shell réel. */
class BuiltinCmdletsTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(tmp.resolve("notes.txt"), "bonjour");
        Files.writeString(tmp.resolve("docs/spec.md"), "# spec");
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("POWERJ_TEST", "1");
        session = new Session(tmp, tmp, env);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false),
                Map.of(), CmdletRegistry.discover());
    }

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void registryFindsTheBuiltinCmdlets() {
        assertThat(interpreter.registry().find("ls")).isPresent();
        assertThat(interpreter.registry().find("env")).isPresent();
    }

    @Test
    void lsDisplaysATable() throws Exception {
        String table = run("ls");
        assertThat(table.lines().toList().getFirst()).matches("name\\s+size\\s+modified\\s+dir");
        assertThat(table).contains("docs", "notes.txt", "7 B", "true", "false");
    }

    @Test
    void lsReturnsObjects() throws Exception {
        assertThat(run("(ls)*.name")).isEqualTo("docs\nnotes.txt\n");
        run("$f = ls -r --files");
        assertThat(run("$f[0].name")).isEqualTo("spec.md\n");
        assertThat(run("$f[-1].size")).isEqualTo("7\n");
        assertThat(run("$f[0].path.parent")).isEqualTo(tmp.resolve("docs") + "\n");
        assertThat(run("$f[0].ext")).isEqualTo("md\n");
    }

    @Test
    void lsOptionErrorsAndHelp() throws Exception {
        run("ls --recurce");
        assertThat(errors).containsExactly("ls : option inconnue --recurce, vouliez-vous dire --recurse ?");
        assertThat(run("ls --help")).contains("ls — Liste les fichiers et dossiers", "-r, --recurse", "Sortie : FileEntry");
        assertThat(run("which ls")).startsWith("ls → cmdlet ("); // nom du module : voir le binaire packagé
    }

    @Test
    void envReadsAndChangesTheSessionEnvironment() throws Exception {
        assertThat(run("(env POWERJ_TEST).value")).isEqualTo("1\n");
        run("env --set POWERJ_AUTRE=deux");
        assertThat(session.environment()).containsEntry("POWERJ_AUTRE", "deux");
        run("env --unset POWERJ_AUTRE");
        assertThat(session.environment()).doesNotContainKey("POWERJ_AUTRE");
        run("env ABSENTE_XYZ");
        assertThat(errors).containsExactly("env : variable absente : ABSENTE_XYZ");
    }

    @Test
    void helpListsCategories() throws Exception {
        assertThat(run("help")).contains("Fichiers", "ls", "Système", "env");
    }

    @Test
    void whereWithBlocks() throws Exception {
        assertThat(run("(ls -r | where { $_.name.endsWith(\".md\") && !$_.dir })*.name")).isEqualTo("spec.md\n");
        assertThat(run("(ls | where { $_.dir })*.name")).isEqualTo("docs\n");
        assertThat(run("(ls -r | where { $_.size > 6 && $_.modified > now - 1d })*.name")).isEqualTo("notes.txt\n");
        assertThat(run("(ls -r | where { List.of(\"md\", \"png\").contains($_.ext) })*.name")).isEqualTo("spec.md\n");
        assertThatThrownBy(() -> run("ls | where { $_.size = 1 }")).hasMessageContaining("utiliser ==");
    }

    @Test
    void whereShortForm() throws Exception {
        assertThat(run("(ls -r | where size > 6)*.name")).isEqualTo("notes.txt\n");
        assertThat(run("(ls -r | where ext == md)*.name")).isEqualTo("spec.md\n");
        assertThat(run("(ls -r | where size >= 6)*.name")).isEqualTo("spec.md\nnotes.txt\n");
        assertThat(run("(ls -r | where name != \"docs\" | where dir == false)*.name")).isEqualTo("spec.md\nnotes.txt\n");
        run("ls | where size");
        assertThat(errors).singleElement().asString().contains("where : condition attendue");
        run("ls | where size ~ 3");
        assertThat(errors).singleElement().asString().contains("where : condition attendue");
    }

    @Test
    void whereErrorsAreNonBlocking() throws Exception {
        assertThat(run("ls | where { $_.size / 0 > 1 }")).isEmpty();
        assertThat(errors).hasSize(2).allSatisfy(e -> assertThat(e).startsWith("where : calcul impossible"));
        run("ls | where { $_.size / 0 > 1 } --on-error silent");
        assertThat(errors).isEmpty();
        run("ls | where { $_.size / 0 > 1 } --on-error stop");
        assertThat(errors).singleElement().asString().startsWith("where : calcul impossible");
    }

    @Test
    void whereOnEnvAndStrings() throws Exception {
        assertThat(run("(env | where { $_.name.startsWith(\"POWERJ_T\") }).value")).isEqualTo("1\n");
        run("$l = (ls)*.name");
        assertThat(run("$l | where { $_.contains(\"o\") }")).isEqualTo("docs\nnotes.txt\n");
        assertThat(run("where --help")).contains("where — Filtre les objets", "ls | where size > 10kb");
        assertThat(run("help")).contains("Filtres", "where");
    }

    @Test
    void lambdasAndMap() throws Exception {
        assertThat(run("(ls -r | where { f -> f.name.endsWith(\".md\") && !f.dir })*.name")).isEqualTo("spec.md\n");
        assertThat(run("ls -r | map { f -> f.name + \" : \" + f.name.length() }"))
                .isEqualTo("docs : 4\nspec.md : 7\nnotes.txt : 9\n");
        assertThat(run("ls | map FileEntry::name")).isEqualTo("docs\nnotes.txt\n");
        assertThat(run("ls --files | map { $_.name.toUpperCase() }")).isEqualTo("NOTES.TXT\n");
        assertThat(run("env POWERJ_TEST | map EnvVar::value")).isEqualTo("1\n");
        assertThat(run("ls | map { f -> f.name.split(\"\\\\.\") }")).isEqualTo("docs\nnotes\ntxt\n");
        assertThat(run("ls | map { f -> null }")).isEmpty();
        run("ls | map 42");
        assertThat(errors).singleElement().asString().contains("map : transformation attendue");
        run("ls | where { f -> f.name }");
        assertThat(errors).hasSize(2).allSatisfy(e -> assertThat(e).contains("le bloc doit renvoyer un booléen"));
        assertThat(run("help")).contains("map");
    }
}
