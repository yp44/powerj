package io.powerj.shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

import io.powerj.core.BuildInfo;

/**
 * Boucle de lecture-exécution du shell (version minimale de l'étape 0) :
 * affiche la bannière et le prompt, reconnaît {@code exit}, signale les commandes inconnues.
 */
public final class Repl {

    private final BufferedReader in;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Supplier<Path> currentDirectory;

    public Repl(BufferedReader in, PrintWriter out, PrintWriter err, Supplier<Path> currentDirectory) {
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.currentDirectory = Objects.requireNonNull(currentDirectory, "currentDirectory");
    }

    /**
     * Exécute la boucle jusqu'à {@code exit} ou la fin de l'entrée.
     *
     * @return le code retour du shell
     */
    public int run(BuildInfo buildInfo) {
        out.println(buildInfo.banner());
        while (true) {
            out.print(prompt());
            out.flush();
            String line = readLine();
            if (line == null) {
                out.println();
                out.flush();
                return 0;
            }
            switch (Command.parse(line)) {
                case Command.Empty _ -> { }
                case Command.Exit(int code) -> {
                    out.flush();
                    return code;
                }
                case Command.Invalid(String message) -> error(message);
                case Command.Unknown(String name) -> error("commande inconnue : " + name);
            }
        }
    }

    String prompt() {
        return "PJ " + currentDirectory.get() + "> ";
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture de l'entrée impossible", e);
        }
    }

    private void error(String message) {
        out.flush();
        err.println(message);
        err.flush();
    }
}
