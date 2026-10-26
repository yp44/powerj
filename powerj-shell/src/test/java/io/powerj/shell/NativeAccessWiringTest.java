package io.powerj.shell;

import static io.powerj.shell.TestTerminal.ENTER;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.BuildInfo;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.Supervisor;

/**
 * The consent of the user to load a module that uses native code (FR-77), from the shell to the loader. The module is a
 * jar with only a manifest: when it is accepted, the loader goes on and complains that it has no cmdlet.
 */
@Timeout(30)
class NativeAccessWiringTest {

    private static final BuildInfo BUILD = new BuildInfo("0.1.0", Runtime.Version.parse("25"));
    private static final String SETTING = "modules.nativeAccess.org.test.nat";

    // A loaded jar stays open by its layer until the JVM stops (Windows): no cleanup.
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tmp;

    private PowerJHome home() throws IOException {
        var home = new PowerJHome(tmp.resolve("home")).createDirectories();
        Files.createDirectories(home.modulesDir());
        try (OutputStream file = Files.newOutputStream(home.modulesDir().resolve("native.jar"))) {
            var manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue("Automatic-Module-Name", "org.test.nat");
            manifest.getMainAttributes().putValue("Enable-Native-Access", "true");
            new JarOutputStream(file, manifest).close();
        }
        return home;
    }

    private List<String> script(PowerJHome home) {
        List<String> errors = new ArrayList<>();
        var script = new ScriptMode(new Session(tmp, tmp, System.getenv()), CmdletRegistry.discover(),
                new PrintWriter(new StringWriter()), errors::add, 120, Optional.empty());
        script.nativeAccess(home.configFile());
        script.loadModules(home.modulesDir());
        return errors;
    }

    private String repl(PowerJHome home, String keys) throws Exception {
        try (var terminal = new TestTerminal(keys)) {
            var reader = ShellReader.create(terminal.terminal(), home, ShellConfig.defaults());
            var repl = InteractiveShell.create(reader, new Supervisor(), new Session(tmp, tmp, System.getenv()),
                    CmdletRegistry.discover());
            repl.nativeAccess(home.configFile());
            terminal.startTyping();
            repl.loadModules(home.modulesDir());
            repl.run(BUILD);
            return terminal.screen();
        }
    }

    @Test
    void aScriptNeverAsksAndDoesNotLoadTheModule() throws Exception {
        var home = home();
        assertThat(script(home)).singleElement().asString()
                .contains("native.jar: not loaded", SETTING + "=true");
        assertThat(home.configFile()).doesNotExist();
    }

    @Test
    void aScriptFollowsTheRecordedAnswer() throws Exception {
        var home = home();
        Files.writeString(home.configFile(), SETTING + "=false\n");
        assertThat(script(home)).singleElement().asString().contains("native access refused", SETTING + "=false");
        Files.writeString(home.configFile(), SETTING + "=true\n");
        assertThat(script(home)).singleElement().asString().contains("no cmdlet"); // accepted, then nothing to load
    }

    @Test
    void theUserIsAskedAtStartupAndTheAnswerIsRecorded() throws Exception {
        var home = home();
        String screen = repl(home, "y" + ENTER + "exit 0" + ENTER);
        assertThat(screen).contains("The module \"org.test.nat\" uses native code", "answer recorded: " + SETTING
                + "=true", "no cmdlet");
        assertThat(Files.readAllLines(home.configFile())).containsExactly(SETTING + "=true");
    }

    @Test
    void aRefusalAtStartupIsRecordedAndTheModuleIsNotLoaded() throws Exception {
        var home = home();
        String screen = repl(home, "n" + ENTER + "exit 0" + ENTER);
        assertThat(screen).contains("native access refused");
        assertThat(Files.readAllLines(home.configFile())).containsExactly(SETTING + "=false");
    }
}
