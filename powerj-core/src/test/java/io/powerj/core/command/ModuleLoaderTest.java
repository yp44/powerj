package io.powerj.core.command;

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
import io.powerj.core.runtime.CommandKind;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;
import io.powerj.core.support.FakeCmdlets;

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
        assertThat(interpreter.commandKind("hello")).isEqualTo(CommandKind.UNKNOWN);
        assertThat(run("mod-load hello.jar")).isEqualTo("hello\n");
        assertThat(interpreter.commandKind("hello")).isEqualTo(CommandKind.CMDLET);
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

    /** Policy that answers {@code answer} and records what it was asked. */
    private static final class RecordingPolicy implements NativeAccessPolicy {
        final List<String> asked = new ArrayList<>();
        final List<List<String>> layers = new ArrayList<>();
        final java.util.Optional<Boolean> answer;

        RecordingPolicy(java.util.Optional<Boolean> answer) {
            this.answer = answer;
        }

        @Override
        public java.util.Optional<Boolean> consent(String module, List<String> layer) {
            asked.add(module);
            layers.add(layer);
            return answer;
        }
    }

    private static final Map<String, String> NATIVE = Map.of("Enable-Native-Access", "true");

    @Test
    void aModuleThatUsesNativeCodeIsNotLoadedWithoutAnAnswer() throws Exception {
        hello(tmp.resolve("native.jar"), "org.test.nat", "nat", NATIVE);
        run("mod-load native.jar");
        assertThat(errors).containsExactly(
                "native.jar: not loaded, module org.test.nat uses native code and no answer is recorded "
                        + "(set modules.nativeAccess.org.test.nat=true in config.properties to allow it)",
                "mod-load: native.jar not loaded");
        assertThat(interpreter.commandKind("nat")).isEqualTo(CommandKind.UNKNOWN);
    }

    @Test
    void aRefusedModuleIsNotLoaded() throws Exception {
        var policy = new RecordingPolicy(java.util.Optional.of(false));
        interpreter.nativeAccess(policy);
        hello(tmp.resolve("native.jar"), "org.test.nat", "nat", NATIVE);
        run("mod-load native.jar");
        assertThat(errors).first().asString().isEqualTo(
                "native.jar: not loaded, native access refused for module org.test.nat "
                        + "(modules.nativeAccess.org.test.nat=false in config.properties)");
        assertThat(interpreter.commandKind("nat")).isEqualTo(CommandKind.UNKNOWN);
        assertThat(policy.asked).containsExactly("org.test.nat");
    }

    @Test
    void anAcceptedModuleIsLoadedWithNativeAccessForItsLayer() throws Exception {
        var policy = new RecordingPolicy(java.util.Optional.of(true));
        interpreter.nativeAccess(policy);
        hello(tmp.resolve("native.jar"), "org.test.nat", "nat", NATIVE);
        assertThat(run("mod-load native.jar")).isEqualTo("nat\n");
        assertThat(errors).isEmpty();
        assertThat(policy.asked).containsExactly("org.test.nat");
        assertThat(policy.layers.getFirst()).containsExactly("org.test.nat");
        Module module = registry.find("nat").orElseThrow().cmdlet().getClass().getModule();
        assertThat(module.isNativeAccessEnabled()).isTrue();
    }

    @Test
    void aModuleWithoutTheAttributeIsNeverAsked() throws Exception {
        var policy = new RecordingPolicy(java.util.Optional.of(false));
        interpreter.nativeAccess(policy);
        hello(tmp.resolve("plain.jar"), "org.test.plain", "plain");
        hello(tmp.resolve("off.jar"), "org.test.off", "off", Map.of("Enable-Native-Access", "false"));
        hello(tmp.resolve("other.jar"), "org.test.other2", "other2", Map.of("Enable-Native-Access", "ALL-UNNAMED"));
        run("mod-load plain.jar off.jar other.jar");
        assertThat(errors).isEmpty();
        assertThat(policy.asked).isEmpty();
        assertThat(registry.find("plain").orElseThrow().cmdlet().getClass().getModule().isNativeAccessEnabled())
                .isFalse();
    }

    @Test
    void theAttributeIsReadWhateverTheCase() throws Exception {
        var policy = new RecordingPolicy(java.util.Optional.of(true));
        interpreter.nativeAccess(policy);
        hello(tmp.resolve("native.jar"), "org.test.nat", "nat", Map.of("Enable-Native-Access", "TRUE"));
        run("mod-load native.jar");
        assertThat(policy.asked).containsExactly("org.test.nat");
    }

    @Test
    void theStartupLoadAsksToo() throws Exception {
        Path modules = Files.createDirectories(tmp.resolve("modules"));
        hello(modules.resolve("native.jar"), "org.test.nat", "nat", NATIVE);
        assertThat(interpreter.loadModules(modules)).singleElement().asString()
                .contains("modules.nativeAccess.org.test.nat=true");
        interpreter.nativeAccess(new RecordingPolicy(java.util.Optional.of(true)));
        Files.createDirectories(tmp.resolve("modules2"));
        hello(tmp.resolve("modules2").resolve("native2.jar"), "org.test.nat2", "nat2", NATIVE);
        assertThat(interpreter.loadModules(tmp.resolve("modules2"))).isEmpty();
        assertThat(interpreter.commandKind("nat2")).isEqualTo(CommandKind.CMDLET);
    }

    @Test
    void thePolicyIsMandatory() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> interpreter.nativeAccess(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void settingNameIsPrefixedByTheModule() {
        assertThat(NativeAccessPolicy.setting("io.powerj.fx")).isEqualTo("modules.nativeAccess.io.powerj.fx");
    }

    /** Module {@code module} providing a cmdlet {@code name}: {@code name -n X -c 2}. */
    private void hello(Path jar, String module, String name) throws IOException {
        hello(jar, module, name, Map.of());
    }

    private void hello(Path jar, String module, String name, Map<String, String> manifest) throws IOException {
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
        jar(jar, module, entries, manifest);
    }

    private static void jar(Path jar, String module, Map<String, byte[]> entries) throws IOException {
        jar(jar, module, entries, Map.of());
    }

    private static void jar(Path jar, String module, Map<String, byte[]> entries, Map<String, String> attributes)
            throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", module);
        attributes.forEach(manifest.getMainAttributes()::putValue);
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
