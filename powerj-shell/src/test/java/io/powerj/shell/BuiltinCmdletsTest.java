package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(run("(ls).name")).isEqualTo("docs\nnotes.txt\n");
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
}
