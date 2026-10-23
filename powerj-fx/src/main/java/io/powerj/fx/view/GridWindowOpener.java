package io.powerj.fx.view;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.stage.Screen;
import javafx.stage.Stage;

import io.powerj.fx.grid.WindowOpener;
import io.powerj.fx.model.GridModel;
import io.powerj.fx.runtime.FxRuntime;

/** Opens the JavaFX window of {@code grid}. Wiring only: whatever has a condition belongs in the model. */
public final class GridWindowOpener implements WindowOpener {

    /** One toolkit per JVM. */
    private static final FxRuntime RUNTIME = new FxRuntime(new JavaFxPlatform());

    @Override
    public void open(String title, GridModel model) {
        RUNTIME.runAndWait(() -> {
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            Stage stage = new Stage();
            stage.setTitle(title);
            Scene scene = new Scene(new GridView(model).root(), Math.min(1200, screen.getWidth() * 0.8),
                    Math.min(700, screen.getHeight() * 0.8));
            scene.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    stage.close();
                }
            });
            stage.setScene(scene);
            stage.show();
        });
    }
}
