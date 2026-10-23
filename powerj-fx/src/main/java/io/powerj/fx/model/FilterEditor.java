package io.powerj.fx.model;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the user typed in the filters, and the {@link FilterSpec} that results. An expression that is not valid keeps
 * the last good filter of its field (the table does not jump while the user types) and is reported with
 * {@link #error(String)}.
 */
public final class FilterEditor {

    private final List<ColumnSpec> columns;
    private final FilterParser parser;
    private final Map<String, Parsed> good = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private final Map<String, Parsed> searchGood = new LinkedHashMap<>();
    private String searchError;
    private FilterSpec spec = FilterSpec.NONE;

    public FilterEditor(List<ColumnSpec> columns) {
        this(columns, FilterParser.system());
    }

    public FilterEditor(List<ColumnSpec> columns, FilterParser parser) {
        this.columns = List.copyOf(columns);
        this.parser = parser;
    }

    /** Sets the expression of a column; an empty one removes its filter. Unknown columns are ignored. */
    public FilterSpec setColumn(String column, String text) {
        return setColumn(column, FilterOperator.EQUAL, text);
    }

    /**
     * Sets the filter of a column from the operator of its list and the value typed: {@code >=} and {@code 2kb} filter
     * {@code >=2kb}. The operator only goes with a plain value of a number, a size or a date; any other text is read as it
     * is typed, and so is the text of a text column.
     */
    public FilterSpec setColumn(String column, FilterOperator operator, String text) {
        columns.stream().filter(c -> c.name().equals(column)).findFirst().ifPresent(spec -> {
            boolean typed = spec.kind() == ColumnKind.NUMBER || spec.kind() == ColumnKind.SIZE
                    || spec.kind() == ColumnKind.DATE;
            Parsed parsed = parser.parse(typed ? operator.apply(text) : text, spec.kind(), spec.kind());
            if (!parsed.isValid()) {
                errors.put(column, parsed.error());
            } else {
                errors.remove(column);
                if (parsed.isActive()) {
                    good.put(column, parsed);
                } else {
                    good.remove(column);
                }
            }
        });
        return rebuild();
    }

    /** Sets the search text, looked for in every column; empty removes it. */
    public FilterSpec setSearch(String text) {
        Map<String, Parsed> perColumn = new LinkedHashMap<>();
        String error = null;
        for (ColumnSpec column : columns) {
            Parsed parsed = parser.parse(text, ColumnKind.TEXT, column.kind());
            if (!parsed.isValid()) {
                error = parsed.error();
                break;
            }
            if (parsed.isActive()) {
                perColumn.put(column.name(), parsed);
            }
        }
        searchError = error;
        if (error == null) {
            searchGood.clear();
            searchGood.putAll(perColumn);
        }
        return rebuild();
    }

    /** Removes every filter and the search text. */
    public FilterSpec clear() {
        good.clear();
        errors.clear();
        searchGood.clear();
        searchError = null;
        return rebuild();
    }

    /** Why the last expression of the column is not valid, if it is not. */
    public Optional<String> error(String column) {
        return Optional.ofNullable(errors.get(column));
    }

    /** Why the search text is not valid, if it is not. */
    public Optional<String> searchError() {
        return Optional.ofNullable(searchError);
    }

    public FilterSpec spec() {
        return spec;
    }

    private FilterSpec rebuild() {
        spec = new FilterSpec(good, searchGood);
        return spec;
    }
}
