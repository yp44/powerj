package io.powerj.fx.grid;

import io.powerj.fx.model.GridModel;

/** Opens the window of {@code grid}. The cmdlet only knows this interface, so that it is tested without JavaFX. */
public interface WindowOpener {

    /**
     * Opens a window that shows {@code model} and returns once it is shown; it is not waited for to be closed.
     *
     * @throws io.powerj.fx.runtime.FxUnavailableException if the graphical toolkit cannot be used
     */
    void open(String title, GridModel model);
}
