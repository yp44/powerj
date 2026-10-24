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
 * @param passThru the rows can be ticked and given back to the pipeline ({@code --pass-thru}), with OK and Cancel
 * @param single   with {@code passThru}, only one row can be ticked
 */
public record WindowOptions(String title, ColumnChoice columns, Path settings, boolean passThru, boolean single) {

    public WindowOptions {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(columns, "columns");
    }

    /** A window that only shows the rows. */
    public WindowOptions(String title, ColumnChoice columns, Path settings) {
        this(title, columns, settings, false, false);
    }
}
