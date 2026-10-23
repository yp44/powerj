package io.powerj.fx.grid;

import java.nio.file.Path;
import java.util.Objects;

import io.powerj.fx.model.ColumnChoice;

/**
 * What the window needs to know besides the rows.
 *
 * @param title    title of the window
 * @param columns  the columns asked for
 * @param settings file where the position and size of the window are kept, {@code null} for none
 */
public record WindowOptions(String title, ColumnChoice columns, Path settings) {

    public WindowOptions {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(columns, "columns");
    }
}
