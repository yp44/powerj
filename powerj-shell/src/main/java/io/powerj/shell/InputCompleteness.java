package io.powerj.shell;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Détermine si une saisie est complète ou si le shell doit attendre une ligne de continuation
 * (spécification FR-02) : chaîne non fermée, accolade/parenthèse/crochet ouvert, ou ligne finissant
 * par {@code |}, {@code &&} ou {@code ||}. L'antislash en fin de ligne ne déclenche pas de
 * continuation, pour que {@code cd C:\} reste valide.
 */
final class InputCompleteness {

    /** Résultat de l'analyse. */
    sealed interface Result {
        record Complete() implements Result { }

        /**
         * @param missing      ce qui manque, pour le message ({@code "}"}, {@code "\""}, une commande…)
         * @param openBrackets nombre d'accolades, parenthèses et crochets encore ouverts
         */
        record Incomplete(String missing, int openBrackets) implements Result { }
    }

    private InputCompleteness() {
    }

    static Result check(String input) {
        Deque<Character> open = new ArrayDeque<>();
        boolean inString = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // caractère échappé, y compris \"
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '(' -> open.push(')');
                case '{' -> open.push('}');
                case '[' -> open.push(']');
                case ')', '}', ']' -> {
                    if (!open.isEmpty() && open.peek() == c) {
                        open.pop();
                    }
                }
                default -> { }
            }
        }
        if (inString) {
            return new Result.Incomplete("\"", open.size());
        }
        if (!open.isEmpty()) {
            return new Result.Incomplete(String.valueOf(open.peek()), open.size());
        }
        var trimmed = input.stripTrailing();
        if (trimmed.endsWith("|") || trimmed.endsWith("&&")) {
            return new Result.Incomplete("commande", 0);
        }
        return new Result.Complete();
    }
}
