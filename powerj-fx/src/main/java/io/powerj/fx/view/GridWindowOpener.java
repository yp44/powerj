package io.powerj.fx.view;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import io.powerj.fx.grid.WindowOpener;
import io.powerj.fx.grid.WindowOptions;
import io.powerj.fx.model.GridTable;
import io.powerj.fx.model.LiveTable;
import io.powerj.fx.model.WindowSettings;
import io.powerj.fx.model.WindowState;
import io.powerj.fx.runtime.FxRuntime;

/** Opens the JavaFX window of {@code grid}. Wiring only: whatever has a condition belongs in the model. */
public final class GridWindowOpener implements WindowOpener {

    /** One toolkit per JVM. */
    private static final FxRuntime RUNTIME = new FxRuntime(new JavaFxPlatform());

    @Override
    public void open(WindowOptions options, LiveTable live) {
        RUNTIME.runAndWait(() -> {
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            Stage stage = new Stage();
            stage.setTitle(options.title());
            GridView view = new GridView(new GridTable(), live, options.columns());
            Scene scene = new Scene(view.root(), Math.min(1200, screen.getWidth() * 0.8),
                    Math.min(700, screen.getHeight() * 0.8));
            view.bind(scene, stage::close);
            stage.setScene(scene);
            // The place and the size of last time, brought back on the screen if it has changed.
            if (options.settings() != null) {
                WindowSettings.load(options.settings())
                        .map(state -> state.fit(screen.getMinX(), screen.getMinY(), screen.getWidth(), screen.getHeight()))
                        .ifPresent(state -> {
                            stage.setX(state.x());
                            stage.setY(state.y());
                            stage.setWidth(state.width());
                            stage.setHeight(state.height());
                        });
            }
            stage.setOnHidden(_ -> {
                if (options.settings() != null) {
                    WindowSettings.save(options.settings(),
                            new WindowState(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
                }
                view.stop(); // the pipeline is told to stop reading
            });
            // Closed from outside (--wait and Ctrl+C in the shell): the window goes too.
            live.onClosed(() -> Platform.runLater(stage::close));
            stage.show();
            view.start();
        });
    }
}
