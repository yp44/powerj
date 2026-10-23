package io.powerj.fx.view;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import io.powerj.fx.grid.WindowOpener;
import io.powerj.fx.runtime.FxRuntime;

/** Opens the JavaFX window of {@code grid}. Wiring only: whatever has a condition belongs in the model. */
public final class GridWindowOpener implements WindowOpener {

    /** One toolkit per JVM. */
    private static final FxRuntime RUNTIME = new FxRuntime(new JavaFxPlatform());

    @Override
    public void open(String title, String text) {
        RUNTIME.runAndWait(() -> {
            Label label = new Label(text);
            StackPane root = new StackPane(label);
            root.setPadding(new Insets(24));
            Stage stage = new Stage();
            stage.setTitle(title);
            stage.setScene(new Scene(root, 480, 320));
            stage.show();
        });
    }
}
