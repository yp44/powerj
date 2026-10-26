package io.powerj.fx.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.powerj.api.Bytes;
import io.powerj.fx.grid.WindowOptions;
import io.powerj.fx.model.ColumnChoice;
import io.powerj.fx.model.GridTable;
import io.powerj.fx.model.LiveTable;
import io.powerj.fx.model.ObjectReader;
import io.powerj.fx.model.Reflect;

/**
 * The window itself, with a real toolkit: the only tests that build the view. They need a display (a virtual one is
 * enough: {@code xvfb-run -a ./mvnw -Pfx-ui -pl powerj-fx -am test}), so they are tagged {@code ui} and left out of the
 * ordinary build; the CI runs them in a step of its own (review C-29: the view had no test at all).
 */
@Tag("ui")
@Timeout(60)
class GridViewUiTest {

    private record Entry(String name, @Bytes long size) {
    }

    private static final Duration WAIT = Duration.ofSeconds(10);

    @BeforeAll
    static void startToolkit() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        try {
            Platform.startup(() -> started.complete(null));
        } catch (IllegalStateException alreadyRunning) {
            started.complete(null);
        }
        started.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    @AfterAll
    static void stopToolkit() {
        Platform.exit();
    }

    /** Runs on the toolkit thread and waits for the result. */
    private static <T> T onToolkit(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (TimeoutException e) {
            throw new AssertionError("the toolkit thread did not answer", e);
        }
    }

    /** A window over three files, shown. */
    private record Window(Stage stage, GridView view, List<String> closed) {

        void close() throws Exception {
            onToolkit(() -> {
                view.stop();
                stage.close();
                return null;
            });
        }

        <N extends Node> List<N> all(String selector, Class<N> type) throws Exception {
            return onToolkit(() -> view.root().lookupAll(selector).stream().filter(type::isInstance).map(type::cast)
                    .toList());
        }

        Optional<TextField> filterOf(String column) throws Exception {
            return all(".text-field", TextField.class).stream()
                    .filter(field -> ("Filter of the column " + column).equals(field.getAccessibleText())).findFirst();
        }
    }

    private static Window open() throws Exception {
        LiveTable live = new LiveTable(100);
        var reader = new ObjectReader(Reflect::read);
        for (Entry entry : List.of(new Entry("Parser.java", 14_520), new Entry("README.md", 400),
                new Entry("notes.txt", 12_288))) {
            live.offer(reader.read(entry));
        }
        live.finish(false);
        List<String> closed = new ArrayList<>();
        return onToolkit(() -> {
            var options = new WindowOptions("test", ColumnChoice.ALL, null);
            var view = new GridView(new GridTable(), live, options);
            var scene = new Scene(view.root(), 900, 500);
            view.bind(scene, () -> closed.add("closed"));
            var stage = new Stage();
            stage.setScene(scene);
            stage.show();
            view.start();
            view.root().applyCss();
            view.root().layout();
            return new Window(stage, view, closed);
        });
    }

    /** Polls the toolkit thread until the condition holds: the filters wait 200 ms after the last key. */
    private static void awaitToolkit(String what, Supplier<Boolean> condition) throws Exception {
        long end = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < end) {
            if (onToolkit(condition)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void theRowsArrive() throws Exception {
        Window window = open();
        try {
            awaitToolkit("the status bar to count the rows", () -> statusOf(window).contains("3 rows"));
        } finally {
            window.close();
        }
    }

    private static String statusOf(Window window) {
        return window.view().root().lookupAll(".label").stream().filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText()).filter(text -> text != null && text.contains("row"))
                .findFirst().orElse("");
    }

    @Test
    void everyFieldOfTheWindowHasANameThatAScreenReaderCanSay() throws Exception {
        Window window = open();
        try {
            awaitToolkit("the headers", () -> window.view().root().lookupAll(".text-field").size() >= 3);

            List<TextField> fields = window.all(".text-field", TextField.class);
            assertThat(fields).hasSizeGreaterThanOrEqualTo(3); // the search box, and the filters of name and size
            assertThat(fields).extracting(TextField::getAccessibleText).doesNotContainNull().allMatch(text -> !text.isBlank());
            assertThat(fields).extracting(TextField::getAccessibleText).contains("Search in all columns",
                    "Filter of the column name", "Filter of the column size");
            assertThat(window.all(".combo-box", Node.class)).extracting(Node::getAccessibleText)
                    .containsExactly("Comparison for the column size");
            assertThat(onToolkit(() -> window.view().root().lookup(".table-view").getAccessibleText()))
                    .isEqualTo("Table of the objects received");
        } finally {
            window.close();
        }
    }

    @Test
    void anInvalidFilterIsSaidInWordsAndNotOnlyByAColour() throws Exception {
        Window window = open();
        try {
            awaitToolkit("the headers", () -> window.view().root().lookupAll(".text-field").size() >= 3);
            TextField size = window.filterOf("size").orElseThrow();

            onToolkit(() -> {
                size.setText("abc");
                return null;
            });

            awaitToolkit("the status bar to name the problem", () -> statusOf(window).contains("filter of size: not a size"));
            assertThat(onToolkit(size::getAccessibleHelp)).startsWith("not a size");
            assertThat(onToolkit(size::getStyle)).isNotEmpty();

            onToolkit(() -> {
                size.setText("");
                return null;
            });
            awaitToolkit("the problem to go", () -> !statusOf(window).contains("not a size"));
            assertThat(onToolkit(size::getAccessibleHelp)).isNull();
        } finally {
            window.close();
        }
    }

    @Test
    void escCloses() throws Exception {
        Window window = open();
        try {
            onToolkit(() -> {
                javafx.event.Event.fireEvent(window.view().root(),
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                return null;
            });

            assertThat(window.closed()).containsExactly("closed");
        } finally {
            window.close();
        }
    }
}
