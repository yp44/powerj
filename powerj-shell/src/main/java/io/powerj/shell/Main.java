package io.powerj.shell;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;

import io.powerj.core.BuildInfo;

/** Point d'entrée de {@code powerj.exe}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        var in = new BufferedReader(new InputStreamReader(System.in, consoleCharset("stdin.encoding")));
        var out = new PrintWriter(System.out, false, consoleCharset("stdout.encoding"));
        var err = new PrintWriter(System.err, false, consoleCharset("stderr.encoding"));

        var repl = new Repl(in, out, err, () -> Path.of("").toAbsolutePath());
        int exitCode = repl.run(BuildInfo.current());
        System.exit(exitCode);
    }

    /** Encodage de la console pour le flux donné, ou l'encodage natif de la plateforme à défaut. */
    private static Charset consoleCharset(String property) {
        var name = System.getProperty(property, System.getProperty("native.encoding"));
        return name == null ? Charset.defaultCharset() : Charset.forName(name);
    }
}
