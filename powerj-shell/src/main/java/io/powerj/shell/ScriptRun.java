package io.powerj.shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.nativeexec.NativeEncoding;
import io.powerj.core.runtime.Session;

/**
 * {@code powerj -c "line" [args]} or {@code powerj file.pj [args]} (FR-04d): reads the lines and runs them with
 * {@link ScriptMode}. The arguments that follow are the list {@code $args}.
 */
final class ScriptRun {

    private final Environment environment;
    private final PowerJHome home;
    private final Session session;

    ScriptRun(Environment environment, PowerJHome home, Session session) {
        this.environment = environment;
        this.home = home;
        this.session = session;
    }

    /** @return the exit code of the script, 2 if the command line is wrong or the file cannot be read */
    int run(List<String> args) {
        var messages = environment.messages();
        List<String> lines;
        List<String> arguments;
        if (args.getFirst().equals("-c")) {
            if (args.size() < 2) {
                messages.println(Messages.get("usage"));
                return 2;
            }
            lines = List.of(args.get(1));
            arguments = args.subList(2, args.size());
        } else {
            Path file = environment.workingDirectory().resolve(args.getFirst());
            try {
                lines = Profile.lines(file); // as the profile: UTF-8, no BOM
            } catch (IOException e) {
                messages.println(Messages.get("script.readFailed", args.getFirst(), e.getMessage()));
                return 2;
            }
            arguments = args.subList(1, args.size());
        }
        session.setVariable("args", List.copyOf(arguments)); // $args (FR-04d)
        var out = environment.stdoutWriter();
        var err = environment.stderrWriter();
        Optional<Iterator<String>> input = Optional.empty();
        if (!environment.stdinIsTerminal()) {
            // Incoming lines usually come from a native command (dir /b | powerj -c …): same
            // decoding as for those (FR-40b), POWERJ_NATIVE_ENCODING_STDIN for a specific setting.
            Charset charset = new NativeEncoding().forProgram(Path.of("stdin"), environment.variables());
            var reader = new BufferedReader(new InputStreamReader(environment.stdin(), charset));
            input = Optional.of(reader.lines().iterator());
        }
        int width = environment.console() ? environment.columns() : 10_000;
        var script = new ScriptMode(session, CmdletRegistry.discover(), out, err::println, width, input);
        script.nativeAccess(home.configFile());
        script.loadModules(home.modulesDir());
        int code = script.run(lines);
        out.flush();
        err.flush();
        return code;
    }
}
