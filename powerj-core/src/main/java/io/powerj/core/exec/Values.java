package io.powerj.core.exec;

import java.time.Duration;
import java.util.Locale;

/** Rendu d'une valeur sur une seule ligne : argument de commande, interpolation, cellule de tableau. */
public final class Values {

    private Values() {
    }

    public static String text(Object value) {
        return switch (value) {
            case null -> "";
            case Duration d -> duration(d);
            default -> String.valueOf(value);
        };
    }

    private static String duration(Duration d) {
        if (d.toMinutes() > 0) {
            return "%d min %02d s".formatted(d.toMinutes(), d.toSecondsPart());
        }
        return String.format(Locale.ROOT, "%.3f s", d.toNanos() / 1e9);
    }
}
