package io.powerj.shell;

/**
 * Commande saisie au prompt, telle que comprise par le REPL minimal de l'étape 0.
 * Le vrai langage (parser, pipeline, cmdlets) remplacera cette analyse aux étapes suivantes.
 */
sealed interface Command {

    /** Ligne vide ou composée uniquement d'espaces. */
    record Empty() implements Command { }

    /** {@code exit} ou {@code exit <code>}. */
    record Exit(int code) implements Command { }

    /** Commande syntaxiquement invalide, avec le message à afficher. */
    record Invalid(String message) implements Command { }

    /** Commande non reconnue. */
    record Unknown(String name) implements Command { }

    static Command parse(String line) {
        var words = line.strip().split("\\s+");
        return switch (words) {
            case String[] w when w[0].isEmpty() -> new Empty();
            case String[] w when w[0].equals("exit") && w.length == 1 -> new Exit(0);
            case String[] w when w[0].equals("exit") && w.length == 2 -> parseExitCode(w[1]);
            case String[] w when w[0].equals("exit") -> new Invalid("exit : un seul argument attendu");
            case String[] w -> new Unknown(w[0]);
        };
    }

    private static Command parseExitCode(String text) {
        try {
            return new Exit(Integer.parseInt(text));
        } catch (NumberFormatException _) {
            return new Invalid("exit : code retour invalide '" + text + "'");
        }
    }
}
