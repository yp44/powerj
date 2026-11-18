package io.powerj.core.command;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The name to suggest to someone who typed a name that does not exist: an option ({@code --recurce}) or a command
 * ({@code grpe}). One rule for both, so that a typo is helped the same way everywhere (audit v2, U-02): a hint, never a
 * match, and never a guess between several names.
 */
public final class Suggestions {

    private static final int MAX_DISTANCE = 2;

    private Suggestions() {
    }

    /**
     * The name to suggest for {@code text}, which is none of {@code names}.
     * <ul>
     * <li>exactly one name begins with {@code text}: it is the beginning of that name;</li>
     * <li>several do: none is suggested, the error does not pick one (Tab shows them);</li>
     * <li>none does: a typo, so the one name nearest to it within two edits (an exchange of two letters counts for
     * one), the case apart; none if two names are equally near.</li>
     * </ul>
     */
    public static Optional<String> forName(String text, Collection<String> names) {
        List<String> beginning = text.isEmpty() ? List.of() : names.stream().filter(n -> n.startsWith(text)).toList();
        if (beginning.isEmpty()) {
            return closest(text, names);
        }
        return beginning.stream().distinct().count() == 1 ? Optional.of(beginning.getFirst()) : Optional.empty();
    }

    /** The one name within two edits of {@code name} that is nearer than every other, the case apart. */
    static Optional<String> closest(String name, Collection<String> candidates) {
        String lower = name.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = MAX_DISTANCE + 1;
        boolean tie = false;
        for (String candidate : candidates) {
            int d = distance(lower, candidate.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
                tie = false;
            } else if (d == bestDistance && best != null && !candidate.equals(best)) {
                tie = true;
            }
        }
        return tie ? Optional.empty() : Optional.ofNullable(best);
    }

    /** Edit distance where the exchange of two neighbouring letters is one edit (optimal string alignment). */
    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
