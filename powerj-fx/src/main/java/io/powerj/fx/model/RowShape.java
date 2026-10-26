package io.powerj.fx.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The names of the columns of a row, in order, and where the value of each one is in its cells. The rows read from
 * objects of one type have the same names: they share one shape, so a row costs its cells and not a map of its own
 * (review C-29: 100 000 rows of eight columns held about half a kilobyte each in maps).
 */
final class RowShape {

    private final List<String> names;
    private final Map<String, Integer> index;

    private RowShape(List<String> names) {
        this.names = names;
        this.index = new HashMap<>(names.size() * 2);
        for (int i = 0; i < names.size(); i++) {
            index.put(names.get(i), i);
        }
    }

    /** The shape of these column names, which must be distinct. */
    static RowShape of(List<String> names) {
        return new RowShape(List.copyOf(names));
    }

    List<String> names() {
        return names;
    }

    int size() {
        return names.size();
    }

    /** Where the value of a column is, -1 if the row has no such column. */
    int indexOf(String column) {
        return index.getOrDefault(column, -1);
    }
}
