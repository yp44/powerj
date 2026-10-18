package io.powerj.cmdlets;

import java.util.List;
import java.util.Objects;

import io.powerj.api.Display;

/**
 * Group of objects with the same key, produced by {@code group} (specification FR-71).
 *
 * @param key   value of the key; with several keys, the list of their values
 * @param count number of objects of the group
 * @param items objects of the group, in their input order (empty with {@code --no-items})
 */
@Display(columns = {"key", "count"})
public record Group(Object key, long count, List<Object> items) {

    public Group {
        Objects.requireNonNull(items, "items");
    }
}
