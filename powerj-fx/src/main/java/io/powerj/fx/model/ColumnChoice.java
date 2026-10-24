package io.powerj.fx.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The columns asked for with {@code --columns a,b,c}: they are shown first, in this order, and the others are hidden (the
 * menu of the window shows them). Names are matched without regard to case. Nothing asked for means every column, in
 * order of arrival.
 */
public final class ColumnChoice {

    /** Every column, as it comes. */
    public static final ColumnChoice ALL = new ColumnChoice(List.of());

    /** Asked-for names by lower-case name, in order. */
    private final Map<String, String> wanted = new LinkedHashMap<>();

    private ColumnChoice(List<String> names) {
        for (String name : names) {
            wanted.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
    }

    /**
     * @param list names separated by commas, spaces around them ignored, empty ones and repeats dropped
     * @throws IllegalArgumentException if the list holds no name
     */
    public static ColumnChoice parse(String list) {
        List<String> names = new ArrayList<>();
        for (String name : list.split(",")) {
            if (!name.isBlank()) {
                names.add(name.strip());
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("no column name in: " + list);
        }
        return new ColumnChoice(names);
    }

    /** The names asked for, in order; empty for {@link #ALL}. */
    public List<String> names() {
        return List.copyOf(wanted.values());
    }

    public boolean isAll() {
        return wanted.isEmpty();
    }

    /** {@code true} if the column is shown from the start. */
    public boolean visible(String column) {
        return isAll() || wanted.containsKey(column.toLowerCase(Locale.ROOT));
    }

    /**
     * Where a column that appears goes among the ones there are: the asked-for columns in the order asked, before the
     * others, which keep their order of arrival.
     */
    public int insertIndex(List<String> existing, String column) {
        int mine = rank(column);
        for (int i = 0; i < existing.size(); i++) {
            if (rank(existing.get(i)) > mine) {
                return i;
            }
        }
        return existing.size();
    }

    private int rank(String column) {
        int rank = 0;
        for (String key : wanted.keySet()) {
            if (key.equals(column.toLowerCase(Locale.ROOT))) {
                return rank;
            }
            rank++;
        }
        return Integer.MAX_VALUE;
    }

    /** The names asked for that are not among {@code seen} (lower-case names of the columns there are). */
    public List<String> missing(Set<String> seen) {
        List<String> missing = new ArrayList<>();
        wanted.forEach((key, name) -> {
            if (!seen.contains(key)) {
                missing.add(name);
            }
        });
        return missing;
    }
}
