package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.CmdletProvider;

/**
 * Third-party modules (§4.4): jars compiled during the test, loaded into their own {@link ModuleLayer}.
 * The tests run on the classpath: the jars are automatic modules (the explicit example module
 * {@code greet} is checked by the CI smoke test).
 */
class ModuleLoaderTest {

    // On Windows, a loaded jar stays open by its layer until the JVM stops: the directory cannot
    // be deleted at the end of the test.
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Interpreter interpreter;
    private CmdletRegistry registry;

    @BeforeEach
    void setUp() {
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        registry = CmdletRegistry.of(FakeCmdlets.all());
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                registry);
    }

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void modulesDirectoryIsLoadedAtStartup() throws Exception {
        Path modules = Files.createDirectories(tmp.resolve("modules"));
        hello(modules.resolve("hello.jar"), "org.test.hello", "hello");
        assertThat(interpreter.loadModules(modules)).isEmpty();
        assertThat(run("hello -n Yves -c 2")).isEqualTo("""
                name  message
                ----  -------
                Yves  Hello Yves
                Yves  Hello Yves
                """);
        assertThat(run("$g = hello -n Yves -c 2 ; $g*.message ; $g[1].name") + errors)
                .isEqualTo("Hello Yves\nHello Yves\nYves\n[]");
        assertThat(run("help hello")).contains("hello — Greets", "-n, --name", "Module: org.test.hello");
        assertThat(run("mod-list")).contains("org.test.hello", "[hello]", "hello.jar");
    }

    @Test
    void missingDirectoryLoadsNothing() {
        assertThat(interpreter.loadModules(tmp.resolve("absent"))).isEmpty();
    }

    @Test
    void modLoadAddsCmdletsWhileRunning() throws Exception {
        hello(tmp.resolve("hello.jar"), "org.test.hello", "hello");
        assertThat(interpreter.commandKind("hello")).isEqualTo(Interpreter.CommandKind.UNKNOWN);
        assertThat(run("mod-load hello.jar")).isEqualTo("hello\n");
        assertThat(interpreter.commandKind("hello")).isEqualTo(Interpreter.CommandKind.CMDLET);
        assertThat(run("(hello -n A).message")).isEqualTo("Hello A\n");
        run("mod-load hello.jar");
        assertThat(errors).containsExactly("hello.jar: module org.test.hello already loaded", "mod-load: hello.jar not loaded");
    }

    @Test
    void nameCollisionKeepsTheFirstAndQualifiesTheOther() throws Exception {
        hello(tmp.resolve("other.jar"), "org.test.other", "items");
        run("mod-load other.jar");
        assertThat(errors).singleElement().asString()
                .contains("\"items\"", "org.test.other", "available as other:items");
        assertThat(run("items -n 1")).contains("item1"); // the first one keeps the short name
        assertThat(run("other:items -n Q")).contains("Hello Q");
        assertThat(run("mod-list")).contains("[other:items]");
    }

    @Test
    void builtinNamesAreReserved() throws Exception {
        hello(tmp.resolve("bad.jar"), "org.test.bad", "cd");
        run("mod-load bad.jar");
        assertThat(errors).first().asString().contains("\"cd\"", "name reserved");
    }

    @Test
    void invalidModulesAreReported() throws Exception {
        Files.writeString(tmp.resolve("notes.txt"), "x");
        Files.write(tmp.resolve("broken.jar"), new byte[] {1, 2, 3});
        Path empty = tmp.resolve("empty.jar");
        jar(empty, "org.test.empty", Map.of());
        run("mod-load notes.txt");
        assertThat(errors).first().asString().contains("a module is a .jar file");
        run("mod-load broken.jar");
        assertThat(errors).first().asString().contains("broken.jar: invalid module");
        run("mod-load empty.jar");
        assertThat(errors).first().asString().contains("no cmdlet");
        run("mod-load absent.jar");
        assertThat(errors).first().asString().contains("module not found");
        run("mod-list x");
        assertThat(errors).first().asString().contains("no arguments");
    }

    @Test
    void moduleClassesAreNotJavaExpressions() throws Exception {
        hello(tmp.resolve("hello.jar"), "org.test.hello", "hello");
        run("mod-load hello.jar");
        run("new org.test.hello.Greeting(\"a\", \"b\")");
        assertThat(errors).singleElement().asString().contains("org.test.hello.Greeting");
    }

    /** Module {@code module} providing a cmdlet {@code name}: {@code name -n X -c 2}. */
    private void hello(Path jar, String module, String name) throws IOException {
        String pkg = module;
        Map<String, String> sources = Map.of(
                "Greeting.java", "package " + pkg + "; public record Greeting(String name, String message) { }",
                "Params.java", """
                        package %s;
                        import io.powerj.api.Option;
                        public record Params(@Option(shortName = 'n', mandatory = true, description = "Name") String name,
                                             @Option(shortName = 'c') int count) { }
                        """.formatted(pkg),
                "Hello.java", """
                        package %s;
                        import io.powerj.api.*;
                        @CmdletInfo(name = "%s", summary = "Greets")
                        public final class Hello implements Cmdlet<Params, Void, Greeting> {
                            @Override public void begin(Params p, CmdletContext<Greeting> ctx) {
                                for (int i = 0; i < Math.max(1, p.count()); i++) {
                                    ctx.emit(new Greeting(p.name(), "Hello " + p.name()));
                                }
                            }
                        }
                        """.formatted(pkg, name),
                "Provider.java", """
                        package %s;
                        import java.util.List;
                        import io.powerj.api.*;
                        public final class Provider implements CmdletProvider {
                            @Override public List<Cmdlet<?, ?, ?>> cmdlets() { return List.of(new Hello()); }
                        }
                        """.formatted(pkg));
        Path src = Files.createDirectories(tmp.resolve("src-" + module).resolve(pkg.replace('.', '/')));
        Path classes = Files.createDirectories(tmp.resolve("classes-" + module));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", apiLocation(), "-nowarn"));
        for (var source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        int code = ToolProvider.findFirst("javac").orElseThrow()
                .run(System.out, System.err, args.toArray(String[]::new));
        assertThat(code).as("compilation du module de test").isZero();
        Map<String, byte[]> entries = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                entries.put(classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        entries.put("META-INF/services/" + CmdletProvider.class.getName(),
                (pkg + ".Provider").getBytes(StandardCharsets.UTF_8));
        jar(jar, module, entries);
    }

    private static void jar(Path jar, String module, Map<String, byte[]> entries) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", module);
        try (OutputStream file = Files.newOutputStream(jar); var stream = new JarOutputStream(file, manifest)) {
            for (var entry : entries.entrySet()) {
                stream.putNextEntry(new JarEntry(entry.getKey()));
                stream.write(entry.getValue());
                stream.closeEntry();
            }
        }
    }

    private static String apiLocation() {
        try {
            return Path.of(CmdletProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
