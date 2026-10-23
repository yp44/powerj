package io.powerj.fx.grid;

import io.powerj.fx.model.LiveTable;

/** Opens the window of {@code grid}. The cmdlet only knows this interface, so that it is tested without JavaFX. */
public interface WindowOpener {

    /**
     * Opens a window that shows the rows of {@code live} as they arrive, and returns once it is shown; it is not waited
     * for to be closed. Closing the window calls {@link LiveTable#close()}.
     *
     * @throws io.powerj.fx.runtime.FxUnavailableException if the graphical toolkit cannot be used
     */
    void open(String title, LiveTable live);
}
