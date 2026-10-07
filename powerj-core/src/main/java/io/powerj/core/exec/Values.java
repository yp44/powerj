package io.powerj.core.exec;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rendu texte des valeurs pour l'affichage et les redirections. Version minimale : le formatage en
 * tableaux (spécification FR-30) arrive avec les cmdlets.
 */
public final class Values {

    private Values() {
    }

    /** Lignes affichées pour une valeur : une par élément d'une collection, une par propriété d'un record. */
    public static List<String> lines(Object value) {
        List<String> lines = new ArrayList<>();
        switch (value) {
            case null -> { }
            case java.nio.file.Path path -> lines.add(path.toString()); // un Path est aussi un Iterable<Path>
            case Iterable<?> items -> items.forEach(item -> lines.add(text(item)));
            case Object array when array.getClass().isArray() -> {
                for (int i = 0; i < Array.getLength(array); i++) {
                    lines.add(text(Array.get(array, i)));
                }
            }
            case Record record -> lines.addAll(recordLines(record));
            default -> lines.add(text(value));
        }
        return lines;
    }

    /** Valeur sur une seule ligne (argument de commande, interpolation, cellule). */
    public static String text(Object value) {
        return switch (value) {
            case null -> "";
            case Duration d -> duration(d);
            default -> String.valueOf(value);
        };
    }

    private static List<String> recordLines(Record record) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        int width = 0;
        for (RecordComponent c : components) {
            width = Math.max(width, c.getName().length());
        }
        List<String> lines = new ArrayList<>();
        for (RecordComponent c : components) {
            Object v;
            try {
                v = c.getAccessor().invoke(record);
            } catch (ReflectiveOperationException e) {
                v = "?";
            }
            lines.add(("%-" + width + "s : %s").formatted(c.getName(), text(v)));
        }
        return lines;
    }

    private static String duration(Duration d) {
        if (d.toMinutes() > 0) {
            return "%d min %02d s".formatted(d.toMinutes(), d.toSecondsPart());
        }
        return String.format(Locale.ROOT, "%.3f s", d.toNanos() / 1e9);
    }
}
