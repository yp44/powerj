package io.powerj.fx.view;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import io.powerj.fx.grid.WindowOpener;
import io.powerj.fx.model.GridTable;
import io.powerj.fx.model.LiveTable;
import io.powerj.fx.runtime.FxRuntime;

/** Opens the JavaFX window of {@code grid}. Wiring only: whatever has a condition belongs in the model. */
public final class GridWindowOpener implements WindowOpener {

    /** One toolkit per JVM. */
    private static final FxRuntime RUNTIME = new FxRuntime(new JavaFxPlatform());

    @Override
    public void open(String title, LiveTable live) {
        RUNTIME.runAndWait(() -> {
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            Stage stage = new Stage();
            stage.setTitle(title);
            GridView view = new GridView(new GridTable(), live);
            Scene scene = new Scene(view.root(), Math.min(1200, screen.getWidth() * 0.8),
                    Math.min(700, screen.getHeight() * 0.8));
            view.bind(scene, stage::close);
            stage.setOnHidden(_ -> view.stop()); // the pipeline is told to stop reading
            stage.setScene(scene);
            stage.show();
            view.start();
        });
    }
}
