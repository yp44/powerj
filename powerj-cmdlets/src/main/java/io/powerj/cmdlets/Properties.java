package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import io.powerj.api.Attempt;
import io.powerj.api.CmdletContext;
import io.powerj.text.RecordView;

/**
 * Named values of an object, for {@code to-json}, {@code to-csv} and {@code join} (specification FR-73): the components of
 * a record, the entries of a map ({@code Row} included), or a single column {@code value} for anything else.
 * <p>
 * The <b>names</b> come from {@link RecordView}, which needs no access to the record. The <b>values</b> are read by the
 * context ({@link CmdletContext#readProperty}), never by reflection here: a module can read by reflection only the
 * records of packages that are exported or opened to it, and an object comes from any module (the core, one loaded by
 * {@code mod-load}); the engine alone can read them all (#130).
 */
final class Properties {

    /** Column of an object that has no named values. */
    static final String VALUE = "value";

    /**
     * A component of a record that cannot be read. A dedicated type, so that a caller never has to catch
     * {@link IllegalStateException}, which is also what a {@link CancellationException} is (review C-15): to-json once
     * took Ctrl+C for an object it could not write.
     */
    static final class Unreadable extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        Unreadable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final CmdletContext<?> context;

    /** The reader of one execution: the context of the cmdlet that asks. */
    Properties(CmdletContext<?> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /**
     * Components of a record, in declaration order.
     *
     * @throws Unreadable if a component cannot be read
     */
    Map<String, Object> of(Record record) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : RecordView.of(record.getClass()).names()) {
            values.put(name, read(record, name));
        }
        return values;
    }

    /** Names of the columns of an object. */
    static List<String> columns(Object value) {
        List<String> columns = new ArrayList<>();
        switch (value) {
            case Record record -> columns.addAll(RecordView.of(record.getClass()).names());
            case Map<?, ?> map -> map.keySet().forEach(key -> columns.add(String.valueOf(key)));
            case null, default -> columns.add(VALUE);
        }
        return columns;
    }

    /**
     * Value of a column of an object, {@code null} if it has none.
     *
     * @throws Unreadable if a record component cannot be read
     */
    Object get(Object value, String column) {
        switch (value) {
            case Record record -> {
                return RecordView.of(record.getClass()).indexOf(column) < 0 ? null : read(record, column);
            }
            case Map<?, ?> map -> {
                if (map.containsKey(column)) {
                    return map.get(column);
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) { // keys that are not strings
                    if (String.valueOf(entry.getKey()).equals(column)) {
                        return entry.getValue();
                    }
                }
                return null;
            }
            case null, default -> {
                return VALUE.equals(column) ? value : null;
            }
        }
    }

    /** A component read by the context; an interruption passes through, anything else makes the object unreadable. */
    private Object read(Record record, String name) {
        Attempt<Object> attempt = context.tryReadProperty(record, name);
        if (attempt.failed()) {
            RuntimeException error = attempt.error();
            String message = error.getMessage() != null ? error.getMessage()
                    : Messages.get("properties.read.failed", name, error);
            throw new Unreadable(message, error);
        }
        return attempt.value();
    }
}
