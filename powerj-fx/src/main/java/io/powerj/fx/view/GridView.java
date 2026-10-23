package io.powerj.fx.view;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import io.powerj.fx.Messages;
import io.powerj.fx.model.CellFormatter;
import io.powerj.fx.model.ColumnSpec;
import io.powerj.fx.model.ColumnWidths;
import io.powerj.fx.model.Debouncer;
import io.powerj.fx.model.FilterEditor;
import io.powerj.fx.model.GridModel;
import io.powerj.fx.model.GridRow;
import io.powerj.fx.model.GridTable;
import io.powerj.fx.model.SortDirection;
import io.powerj.fx.model.SortKey;
import io.powerj.fx.model.SortSpec;

/**
 * The table, its filters and its status bar. Wiring only: the text of the cells, the widths, the sort and the filters
 * come from the model.
 */
final class GridView {

    /** Rows read to size a column. */
    private static final int WIDTH_SAMPLE = 200;
    /** A character is about this wide, a column also has its padding. */
    private static final double CHAR_WIDTH = 7.5;
    private static final double PADDING = 28;
    /** Typing in a filter waits this long before the table is filtered. */
    private static final Duration FILTER_DELAY = Duration.ofMillis(200);
    private static final String ERROR_STYLE = "-fx-control-inner-background: #ffdede;";

    private final BorderPane root = new BorderPane();
    private final GridTable data;
    private final FilterEditor editor;
    private final Debouncer debouncer = new Debouncer(new FxScheduler(), FILTER_DELAY);
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final TextField search = new TextField();
    private final Label status = new Label();

    GridView(GridModel model) {
        data = new GridTable(model);
        editor = new FilterEditor(model.columns());
        TableView<GridRow> table = new TableView<>(data.rows());
        table.setPlaceholder(new Label(Messages.get("grid.empty")));
        for (ColumnSpec spec : model.columns()) {
            table.getColumns().add(column(spec, model));
        }
        // The header does the clicking (ascending, descending, none; Shift adds a column) and the model does the sorting.
        table.setSortPolicy(view -> {
            data.sort(sortSpec(view));
            return true;
        });

        search.setPromptText(Messages.get("grid.search.prompt"));
        search.textProperty().addListener((_, _, _) -> debouncer.submit(this::refresh));
        Button clear = new Button(Messages.get("grid.clear"));
        clear.setOnAction(_ -> clearFilters());
        HBox bar = new HBox(6, search, clear);
        HBox.setHgrow(search, Priority.ALWAYS);
        bar.setPadding(new Insets(6));

        status.setPadding(new Insets(4, 8, 4, 8));
        data.rows().addListener((InvalidationListener) _ -> updateStatus());
        updateStatus();

        root.setTop(bar);
        root.setCenter(table);
        root.setBottom(status);
    }

    Parent root() {
        return root;
    }

    /**
     * Keyboard: Ctrl+F goes to the search box; Esc empties the text field that has the focus, or else closes the window.
     */
    void bind(Scene scene, Runnable close) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN),
                () -> {
                    search.requestFocus();
                    search.selectAll();
                });
        scene.setOnKeyPressed(event -> {
            if (event.getCode() != KeyCode.ESCAPE) {
                return;
            }
            Node focus = scene.getFocusOwner();
            if (focus instanceof TextField field && !field.getText().isEmpty()) {
                field.clear();
                debouncer.cancel();
                refresh();
            } else {
                close.run();
            }
        });
    }

    /** Applies what is typed in the filters. */
    private void refresh() {
        for (Map.Entry<String, TextField> field : fields.entrySet()) {
            editor.setColumn(field.getKey(), field.getValue().getText());
            showError(field.getValue(), editor.error(field.getKey()).orElse(null));
        }
        editor.setSearch(search.getText());
        showError(search, editor.searchError().orElse(null));
        data.filter(editor.spec());
    }

    private void clearFilters() {
        fields.values().forEach(TextField::clear);
        search.clear();
        debouncer.cancel();
        refresh();
    }

    private static void showError(TextField field, String error) {
        field.setStyle(error == null ? "" : ERROR_STYLE);
        field.setTooltip(error == null ? null : new Tooltip(error));
    }

    private void updateStatus() {
        int shown = data.rows().size();
        int total = data.total();
        status.setText(shown != total ? Messages.get("grid.rows.filtered", shown, total)
                : Messages.get(total == 1 ? "grid.rows.one" : "grid.rows", total));
    }

    /** The columns the table is sorted by, in the order of the clicks. */
    private static SortSpec sortSpec(TableView<GridRow> table) {
        List<SortKey> keys = new ArrayList<>();
        for (TableColumn<GridRow, ?> column : table.getSortOrder()) {
            keys.add(new SortKey(column.getId(), column.getSortType() == TableColumn.SortType.ASCENDING
                    ? SortDirection.ASCENDING : SortDirection.DESCENDING));
        }
        return new SortSpec(keys);
    }

    private TableColumn<GridRow, GridRow> column(ColumnSpec spec, GridModel model) {
        TableColumn<GridRow, GridRow> column = new TableColumn<>();
        column.setId(spec.name());
        column.setGraphic(header(spec, column));
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        column.setCellFactory(_ -> new TableCell<>() {
            @Override
            protected void updateItem(GridRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                Object value = row.get(spec.name());
                setText(CellFormatter.text(value, spec.kind()));
                String full = CellFormatter.tooltip(value, spec.kind());
                setTooltip(full == null ? null : new Tooltip(full));
                setAlignment(spec.kind().alignRight() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            }
        });
        column.setPrefWidth(ColumnWidths.chars(spec, model.rows(), WIDTH_SAMPLE) * CHAR_WIDTH + PADDING);
        return column;
    }

    /** The name of the column, and under it the field where its filter is typed. */
    private Node header(ColumnSpec spec, TableColumn<GridRow, GridRow> column) {
        Label title = new Label(spec.name());
        title.setStyle("-fx-font-weight: bold;");
        TextField field = new TextField();
        field.setPromptText(Messages.get("grid.filter.prompt"));
        field.setMinWidth(40);
        field.prefWidthProperty().bind(Bindings.max(40, column.widthProperty().subtract(30)));
        field.textProperty().addListener((_, _, _) -> debouncer.submit(this::refresh));
        fields.put(spec.name(), field);
        VBox box = new VBox(2, title, field);
        box.setAlignment(Pos.CENTER);
        return box;
    }
}
