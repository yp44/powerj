package io.powerj.fx.view;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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
import io.powerj.fx.model.ColumnKind;
import io.powerj.fx.model.FilterEditor;
import io.powerj.fx.model.FilterOperator;
import io.powerj.fx.model.GridRow;
import io.powerj.fx.model.GridTable;
import io.powerj.fx.model.LiveTable;
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
    /** Width of a numeric column: the list of comparisons, and room to type a value. */
    private static final double MIN_FILTERED_NUMBER = 150;
    private static final double OPERATOR_WIDTH = 66;
    /** The table takes what arrived this often, and at most this many rows each time. */
    private static final int FEED_PERIOD_MILLIS = 100;
    private static final int FEED_BATCH = 20_000;
    private static final String ERROR_STYLE = "-fx-control-inner-background: #ffdede;";

    private final BorderPane root = new BorderPane();
    private final GridTable data;
    private final LiveTable live;
    private final TableView<GridRow> table;
    private final Label placeholder = new Label();
    /** Moves the rows that arrived into the table, a batch at a time. */
    private final Timeline feed = new Timeline(new KeyFrame(javafx.util.Duration.millis(FEED_PERIOD_MILLIS), _ -> tick()));
    private final FilterEditor editor;
    private final Debouncer debouncer = new Debouncer(new FxScheduler(), FILTER_DELAY);
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    /** The kind each column was built for. */
    private final Map<String, ColumnKind> shown = new LinkedHashMap<>();
    /** The list of comparisons before the filter of a numeric column. */
    private final Map<String, ComboBox<FilterOperator>> operators = new LinkedHashMap<>();
    private final TextField search = new TextField();
    private final Label status = new Label();

    GridView(GridTable data, LiveTable live) {
        this.data = data;
        this.live = live;
        editor = new FilterEditor(data.columns());
        table = new TableView<>(data.rows());
        placeholder.setText(Messages.get("grid.loading"));
        table.setPlaceholder(placeholder);
        data.columns().forEach(this::addColumn);
        // Columns arrive with the objects: a new kind of object can bring new ones.
        data.columns().addListener((ListChangeListener<ColumnSpec>) this::columnsChanged);
        feed.setCycleCount(Timeline.INDEFINITE);
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

    /** Starts moving the rows that arrive into the table. */
    void start() {
        feed.play();
        tick();
    }

    /** The window is closed: stops reading the pipeline. */
    void stop() {
        feed.stop();
        live.close();
    }

    private void tick() {
        live.drain(data, FEED_BATCH);
        updateStatus();
        if (live.state() != LiveTable.State.LOADING && live.pending() == 0) {
            feed.stop();
        }
    }

    private void columnsChanged(ListChangeListener.Change<? extends ColumnSpec> change) {
        while (change.next()) {
            if (change.wasReplaced()) {
                for (ColumnSpec spec : change.getAddedSubList()) {
                    replaceColumn(spec);
                }
            } else if (change.wasAdded()) {
                change.getAddedSubList().forEach(this::addColumn);
            }
        }
        refresh(); // the search text is looked for in the new columns too
    }

    private void addColumn(ColumnSpec spec) {
        table.getColumns().add(column(spec));
    }

    /** A column whose kind became clearer: shown as a number, say, once its first numbers arrived. */
    private void replaceColumn(ColumnSpec spec) {
        for (int i = 0; i < table.getColumns().size(); i++) {
            TableColumn<GridRow, ?> old = table.getColumns().get(i);
            if (spec.name().equals(old.getId())) {
                if (sameLook(shown.get(spec.name()), spec.kind())) {
                    shown.put(spec.name(), spec.kind());
                    return;
                }
                String text = fields.get(spec.name()).getText();
                ComboBox<FilterOperator> operator = operators.get(spec.name());
                FilterOperator chosen = operator == null ? null : operator.getValue();
                TableColumn<GridRow, GridRow> replacement = column(spec);
                replacement.setSortType(old.getSortType());
                table.getColumns().set(i, replacement);
                fields.get(spec.name()).setText(text);
                if (chosen != null && operators.get(spec.name()) != null) {
                    operators.get(spec.name()).setValue(chosen);
                }
                return;
            }
        }
    }

    /** Whether two kinds are shown the same way: only the numbers, sizes and dates look different. */
    private static boolean sameLook(ColumnKind a, ColumnKind b) {
        boolean plainA = a == null || a == ColumnKind.UNKNOWN || a == ColumnKind.TEXT;
        boolean plainB = b == ColumnKind.UNKNOWN || b == ColumnKind.TEXT;
        return a == b || plainA && plainB;
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
        editor.columns(data.columns());
        for (Map.Entry<String, TextField> field : fields.entrySet()) {
            ComboBox<FilterOperator> operator = operators.get(field.getKey());
            editor.setColumn(field.getKey(), operator == null ? FilterOperator.EQUAL : operator.getValue(),
                    field.getValue().getText());
            showError(field.getValue(), editor.error(field.getKey()).orElse(null));
        }
        editor.setSearch(search.getText());
        showError(search, editor.searchError().orElse(null));
        data.filter(editor.spec());
    }

    private void clearFilters() {
        fields.values().forEach(TextField::clear);
        operators.values().forEach(operator -> operator.setValue(FilterOperator.EQUAL));
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
        String rows = shown != total ? Messages.get("grid.rows.filtered", shown, total)
                : Messages.get(total == 1 ? "grid.rows.one" : "grid.rows", total);
        LiveTable.State state = live.state();
        String note = switch (state) {
            case LOADING -> Messages.get("grid.state.loading");
            case COMPLETE -> "";
            case TRUNCATED -> Messages.get("grid.state.truncated", total);
            case INTERRUPTED -> Messages.get("grid.state.interrupted");
        };
        status.setText(note.isEmpty() ? rows : rows + "  ·  " + note);
        placeholder.setText(Messages.get(state == LiveTable.State.LOADING ? "grid.loading" : "grid.empty"));
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

    private TableColumn<GridRow, GridRow> column(ColumnSpec spec) {
        shown.put(spec.name(), spec.kind());
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
        double width = ColumnWidths.chars(spec, data.rows(), WIDTH_SAMPLE) * CHAR_WIDTH + PADDING;
        // The list of comparisons and a few characters of filter must fit under the name.
        column.setPrefWidth(hasOperators(spec) ? Math.max(width, MIN_FILTERED_NUMBER) : width);
        return column;
    }

    /** Numbers and sizes are filtered with a comparison chosen in a list. */
    private static boolean hasOperators(ColumnSpec spec) {
        return spec.kind() == ColumnKind.NUMBER || spec.kind() == ColumnKind.SIZE;
    }

    /** The name of the column, and under it the field where its filter is typed (after a list of comparisons for numbers). */
    private Node header(ColumnSpec spec, TableColumn<GridRow, GridRow> column) {
        Label title = new Label(spec.name());
        title.setStyle("-fx-font-weight: bold;");
        TextField field = new TextField();
        field.setPromptText(Messages.get("grid.filter.prompt"));
        field.setMinWidth(40);
        field.textProperty().addListener((_, _, _) -> debouncer.submit(this::refresh));
        fields.put(spec.name(), field);
        Node filter = field;
        double beside = 0;
        if (hasOperators(spec)) {
            ComboBox<FilterOperator> operator = new ComboBox<>();
            operator.getItems().addAll(FilterOperator.values());
            operator.setValue(FilterOperator.EQUAL);
            operator.setPrefWidth(OPERATOR_WIDTH);
            operator.setMinWidth(OPERATOR_WIDTH);
            operator.setTooltip(new Tooltip(Messages.get("grid.operator.tooltip")));
            operator.valueProperty().addListener((_, _, _) -> debouncer.submit(this::refresh));
            operators.put(spec.name(), operator);
            filter = new HBox(2, operator, field);
            beside = OPERATOR_WIDTH + 2;
        }
        field.prefWidthProperty().bind(Bindings.max(40, column.widthProperty().subtract(30 + beside)));
        VBox box = new VBox(2, title, filter);
        box.setAlignment(Pos.CENTER);
        return box;
    }
}
