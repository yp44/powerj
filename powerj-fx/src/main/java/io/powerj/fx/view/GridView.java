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
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import io.powerj.fx.Messages;
import io.powerj.fx.grid.WindowOptions;
import io.powerj.fx.model.CellFormatter;
import io.powerj.fx.model.ColumnChoice;
import io.powerj.fx.model.ColumnKind;
import io.powerj.fx.model.ColumnSpec;
import io.powerj.fx.model.ColumnWidths;
import io.powerj.fx.model.Debouncer;
import io.powerj.fx.model.FilterEditor;
import io.powerj.fx.model.FilterOperator;
import io.powerj.fx.model.GridRow;
import io.powerj.fx.model.GridTable;
import io.powerj.fx.model.LiveTable;
import io.powerj.fx.model.RowSelection;
import io.powerj.fx.model.RowExporter;
import io.powerj.fx.model.SortDirection;
import io.powerj.fx.model.SortKey;
import io.powerj.fx.model.SortSpec;

/**
 * The table, its filters, its menus and its status bar. Wiring only: the text of the cells, the widths, the sort, the
 * filters, the choice of columns and the text that is copied come from the model.
 */
final class GridView {

    /** Rows read to size a column. */
    private static final int WIDTH_SAMPLE = 200;
    /** Rows read to fit a column to its content on demand. */
    private static final int FIT_SAMPLE = 2_000;
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
    /** Id of the column of tick boxes ({@code --pass-thru}): not a column of the data. */
    private static final String TICK_ID = "\u0000tick";
    private static final double TICK_WIDTH = 40;
    private static final String ERROR_STYLE = "-fx-control-inner-background: #ffdede;";

    private final BorderPane root = new BorderPane();
    private final GridTable data;
    private final LiveTable live;
    private final ColumnChoice choice;
    /** The ticked rows with {@code --pass-thru}, {@code null} otherwise. */
    private final RowSelection selection;
    private Runnable closeWindow = () -> { };
    private final TableView<GridRow> table;
    private final Label placeholder = new Label();
    /** Moves the rows that arrived into the table, a batch at a time. */
    private final Timeline feed = new Timeline(new KeyFrame(javafx.util.Duration.millis(FEED_PERIOD_MILLIS), _ -> tick()));
    private final FilterEditor editor;
    private final Debouncer debouncer = new Debouncer(new FxScheduler(), FILTER_DELAY);
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    /** The list of comparisons before the filter of a numeric column. */
    private final Map<String, ComboBox<FilterOperator>> operators = new LinkedHashMap<>();
    /** The kind each column was built for. */
    private final Map<String, ColumnKind> shown = new LinkedHashMap<>();
    /** The entry of the menu of the columns that shows or hides each column. */
    private final Map<String, CheckMenuItem> columnItems = new LinkedHashMap<>();
    private final MenuButton columnsMenu = new MenuButton(Messages.get("grid.columns.menu"));
    private final TextField search = new TextField();
    private final Label status = new Label();
    private final Button pause = new Button();
    private final TextArea details = new TextArea();
    private final ToggleButton detailsToggle = new ToggleButton(Messages.get("grid.details"));
    private final SplitPane split = new SplitPane();

    GridView(GridTable data, LiveTable live, WindowOptions options) {
        this.data = data;
        this.live = live;
        this.choice = options.columns();
        this.selection = options.passThru() ? new RowSelection(options.single()) : null;
        editor = new FilterEditor(data.columns());
        table = new TableView<>(data.rows());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        placeholder.setText(Messages.get("grid.loading"));
        table.setPlaceholder(placeholder);
        if (selection != null) {
            // The check boxes of a table are disabled unless the table, the column and the cell can be edited.
            table.setEditable(true);
            table.getColumns().add(tickColumn());
        }
        data.columns().forEach(this::addColumn);
        // Columns arrive with the objects: a new kind of object can bring new ones.
        data.columns().addListener((ListChangeListener<ColumnSpec>) this::columnsChanged);
        feed.setCycleCount(Timeline.INDEFINITE);
        // The header does the clicking (ascending, descending, none; Shift adds a column) and the model does the sorting.
        table.setSortPolicy(view -> {
            data.sort(sortSpec(view));
            return true;
        });
        table.setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                copyRows(event.isShiftDown());
                event.consume();
            }
        });

        search.setPromptText(Messages.get("grid.search.prompt"));
        search.textProperty().addListener((_, _, _) -> debouncer.submit(this::refresh));
        Button clear = new Button(Messages.get("grid.clear"));
        clear.setOnAction(_ -> clearFilters());
        detailsToggle.setTooltip(new Tooltip(Messages.get("grid.details.tooltip")));
        detailsToggle.setFocusTraversable(false);
        detailsToggle.selectedProperty().addListener((_, _, on) -> showDetails(on));
        HBox bar = new HBox(6, search, columnsMenu, detailsToggle, clear);
        if (selection != null) {
            Button selectAll = new Button(Messages.get("grid.select.all"));
            selectAll.setOnAction(_ -> selection.selectAll(List.copyOf(data.rows())));
            selectAll.setVisible(!selection.isSingle());
            selectAll.setManaged(!selection.isSingle());
            Button unselectAll = new Button(Messages.get("grid.select.none"));
            unselectAll.setOnAction(_ -> selection.unselectAll(List.copyOf(data.rows())));
            bar.getChildren().addAll(1, List.of(selectAll, unselectAll));
        }
        HBox.setHgrow(search, Priority.ALWAYS);
        bar.setPadding(new Insets(6));

        details.setEditable(false);
        details.setWrapText(true);
        details.setStyle("-fx-font-family: monospace;");
        table.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> updateDetails());
        split.setOrientation(Orientation.HORIZONTAL);
        split.getItems().add(table);

        pause.setOnAction(_ -> {
            live.setPaused(!live.isPaused());
            updateStatus();
        });
        pause.setFocusTraversable(false);
        HBox bottom = new HBox(10, pause, status);
        if (selection != null) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button ok = new Button(Messages.get("grid.ok"));
            ok.setOnAction(_ -> {
                live.accept(selection.selected(data.sort().comparator()));
                closeWindow.run();
            });
            Button cancel = new Button(Messages.get("grid.cancel"));
            cancel.setOnAction(_ -> closeWindow.run());
            bottom.getChildren().addAll(spacer, ok, cancel);
            selection.count().addListener((InvalidationListener) _ -> updateStatus());
        }
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(3, 8, 3, 8));
        data.rows().addListener((InvalidationListener) _ -> updateStatus());
        updateStatus();

        root.setTop(bar);
        root.setCenter(split);
        root.setBottom(bottom);
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

    // --- columns ---

    private void addColumn(ColumnSpec spec) {
        TableColumn<GridRow, GridRow> column = column(spec);
        column.setVisible(choice.visible(spec.name()));
        int index = choice.insertIndex(columnNames(), spec.name());
        table.getColumns().add(index + tickOffset(), column);
        CheckMenuItem item = new CheckMenuItem(spec.name());
        item.selectedProperty().bindBidirectional(column.visibleProperty());
        columnItems.put(spec.name(), item);
        columnsMenu.getItems().add(Math.min(index, columnsMenu.getItems().size()), item);
    }

    /** The columns of the data, in the order of the table (the column of ticks is not one of them). */
    private List<String> columnNames() {
        return table.getColumns().stream().map(TableColumn::getId).filter(id -> !TICK_ID.equals(id)).toList();
    }

    /** The column of ticks, when there is one, comes first. */
    private int tickOffset() {
        return selection == null ? 0 : 1;
    }

    /** The column of check boxes of {@code --pass-thru}: one per row, bound to the selection. */
    private TableColumn<GridRow, Boolean> tickColumn() {
        TableColumn<GridRow, Boolean> column = new TableColumn<>();
        column.setId(TICK_ID);
        column.setCellValueFactory(cell -> selection.property(cell.getValue()));
        column.setCellFactory(CheckBoxTableCell.forTableColumn(column));
        column.setGraphic(new VBox(new Label(Messages.get("grid.select.header"))));
        column.setPrefWidth(TICK_WIDTH);
        column.setMinWidth(TICK_WIDTH);
        column.setMaxWidth(TICK_WIDTH);
        column.setResizable(false);
        column.setSortable(false);
        column.setReorderable(false);
        column.setEditable(true);
        return column;
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
                CheckMenuItem oldItem = columnItems.get(spec.name());
                TableColumn<GridRow, GridRow> replacement = column(spec);
                replacement.setSortType(old.getSortType());
                replacement.setVisible(old.isVisible());
                oldItem.selectedProperty().unbindBidirectional(old.visibleProperty());
                oldItem.selectedProperty().bindBidirectional(replacement.visibleProperty());
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
            private final ContextMenu menu = rowMenu(this);

            @Override
            protected void updateItem(GridRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setTooltip(null);
                    setContextMenu(null);
                    return;
                }
                Object value = row.get(spec.name());
                setText(CellFormatter.text(value, spec.kind()));
                String full = CellFormatter.tooltip(value, spec.kind());
                setTooltip(full == null ? null : new Tooltip(full));
                setAlignment(spec.kind().alignRight() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
                setContextMenu(menu);
            }

            /** Right click on a cell. */
            private ContextMenu rowMenu(TableCell<GridRow, GridRow> cell) {
                MenuItem copyCell = new MenuItem(Messages.get("grid.copy.cell"));
                copyCell.setOnAction(_ -> {
                    GridRow row = cell.getItem();
                    if (row != null) {
                        copy(CellFormatter.fullText(row.get(spec.name()), spec.kind()));
                    }
                });
                MenuItem copyRows = new MenuItem(Messages.get("grid.copy.rows"));
                copyRows.setOnAction(_ -> copyRows(false));
                MenuItem copyJson = new MenuItem(Messages.get("grid.copy.json"));
                copyJson.setOnAction(_ -> copyRows(true));
                return new ContextMenu(copyCell, copyRows, copyJson);
            }
        });
        double width = ColumnWidths.chars(spec, data.rows(), WIDTH_SAMPLE) * CHAR_WIDTH + PADDING;
        // The list of comparisons and a few characters of filter must fit under the name.
        column.setPrefWidth(hasOperators(spec) ? Math.max(width, MIN_FILTERED_NUMBER) : width);
        MenuItem fit = new MenuItem(Messages.get("grid.column.fit"));
        fit.setOnAction(_ -> column.setPrefWidth(fitWidth(spec)));
        MenuItem hide = new MenuItem(Messages.get("grid.column.hide"));
        hide.setOnAction(_ -> column.setVisible(false));
        column.setContextMenu(new ContextMenu(fit, hide));
        return column;
    }

    /** The width the content of a column needs, from the first rows. */
    private double fitWidth(ColumnSpec spec) {
        double width = ColumnWidths.chars(spec, data.rows(), FIT_SAMPLE) * CHAR_WIDTH + PADDING;
        return hasOperators(spec) ? Math.max(width, MIN_FILTERED_NUMBER) : width;
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

    // --- copy, details ---

    /** The rows that are selected, in the order of the table. */
    private List<GridRow> selectedRows() {
        return table.getSelectionModel().getSelectedIndices().stream().sorted()
                .map(i -> table.getItems().get(i)).toList();
    }

    /** The columns that are shown, in the order of the table. */
    private List<ColumnSpec> visibleColumns() {
        Map<String, ColumnSpec> byName = new LinkedHashMap<>();
        data.columns().forEach(spec -> byName.put(spec.name(), spec));
        List<ColumnSpec> visible = new ArrayList<>();
        for (TableColumn<GridRow, ?> column : table.getColumns()) {
            ColumnSpec spec = byName.get(column.getId());
            if (column.isVisible() && spec != null) {
                visible.add(spec);
            }
        }
        return visible;
    }

    /** Ctrl+C: the selected rows as tab-separated text, with Shift as JSON. */
    private void copyRows(boolean json) {
        List<GridRow> rows = selectedRows();
        if (!rows.isEmpty()) {
            copy(json ? RowExporter.json(visibleColumns(), rows) : RowExporter.tsv(visibleColumns(), rows));
        }
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void showDetails(boolean on) {
        if (on && !split.getItems().contains(details)) {
            split.getItems().add(details);
            split.setDividerPositions(0.68);
            updateDetails();
        } else if (!on) {
            split.getItems().remove(details);
        }
    }

    private void updateDetails() {
        GridRow row = table.getSelectionModel().getSelectedItem();
        details.setText(row == null ? "" : RowExporter.details(data.columns(), row));
    }

    // --- keyboard ---

    /**
     * Keyboard: Ctrl+F goes to the search box, F3 shows the details of the selected row; Esc empties the text field that has
     * the focus, or else closes the window.
     */
    void bind(Scene scene, Runnable close) {
        this.closeWindow = close;
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN),
                () -> {
                    search.requestFocus();
                    search.selectAll();
                });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F3),
                () -> detailsToggle.setSelected(!detailsToggle.isSelected()));
        // A filter, not a handler: an editable table (--pass-thru) keeps Esc for itself, to cancel an edit.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.ESCAPE) {
                return;
            }
            Node focus = scene.getFocusOwner();
            if (focus instanceof ComboBoxBase<?> list && list.isShowing()) {
                return; // Esc closes the list
            }
            event.consume();
            if (focus instanceof TextField field && !field.getText().isEmpty()) {
                field.clear();
                debouncer.cancel();
                refresh();
            } else {
                close.run();
            }
        });
    }

    // --- filters and status ---

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
        boolean paused = live.isPaused();
        // Nothing to pause once everything has arrived (and is shown).
        boolean over = state != LiveTable.State.LOADING && live.pending() == 0;
        pause.setDisable(over);
        pause.setText(Messages.get(paused ? "grid.resume" : "grid.pause"));
        // Paused with rows still waiting: even if the input is over, the table is not complete.
        String note = paused && !over ? Messages.get("grid.state.paused") : switch (state) {
            case LOADING -> Messages.get("grid.state.loading");
            case COMPLETE -> "";
            case TRUNCATED -> Messages.get("grid.state.truncated", total);
            case INTERRUPTED -> Messages.get("grid.state.interrupted");
        };
        if (selection != null) {
            rows += "  ·  " + Messages.get("grid.selected", selection.count().get());
        }
        status.setText(note.isEmpty() ? rows : rows + "  ·  " + note);
        placeholder.setText(Messages.get(state == LiveTable.State.LOADING ? "grid.loading" : "grid.empty"));
    }
}
