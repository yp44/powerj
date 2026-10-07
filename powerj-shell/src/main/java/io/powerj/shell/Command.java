package io.powerj.shell;

/**
 * Commande interne du REPL. Le vrai langage (parser, pipeline, cmdlets) arrivera aux étapes
 * suivantes ; ici, seules les commandes internes sont reconnues.
 */
sealed interface Command {

    /** Ligne vide ou composée uniquement de blancs. */
    record Empty() implements Command { }

    /** {@code exit} ou {@code exit <code>}. */
    record Exit(int code) implements Command { }

    /** {@code history} (liste) ou {@code history --clear} (vidage). */
    record History(boolean clear) implements Command { }

    /** Commande invalide, avec le message à afficher. */
    record Invalid(String message) implements Command { }

    /** Commande non reconnue. */
    record Unknown(String name) implements Command { }

    static Command parse(String line) {
        var words = line.strip().split("\\s+");
        return switch (words) {
            case String[] w when w[0].isEmpty() -> new Empty();
            case String[] w when w[0].equals("exit") -> parseExit(w);
            case String[] w when w[0].equals("history") -> parseHistory(w);
            // Une expansion d'historique réussie ne laisse jamais de « ! » en tête de ligne.
            case String[] w when w[0].startsWith("!") && w[0].length() > 1 ->
                    new Invalid("historique : aucune commande ne correspond à " + w[0]);
            case String[] w -> new Unknown(w[0]);
        };
    }

    private static Command parseExit(String[] words) {
        return switch (words.length) {
            case 1 -> new Exit(0);
            case 2 -> {
                try {
                    yield new Exit(Integer.parseInt(words[1]));
                } catch (NumberFormatException _) {
                    yield new Invalid("exit : code retour invalide '" + words[1] + "'");
                }
            }
            default -> new Invalid("exit : un seul argument attendu");
        };
    }

    private static Command parseHistory(String[] words) {
        if (words.length == 1) {
            return new History(false);
        }
        if (words.length == 2 && words[1].equals("--clear")) {
            return new History(true);
        }
        return new Invalid("history : option inconnue '" + words[words.length - 1]
                + "' (option disponible : --clear)");
    }
}
