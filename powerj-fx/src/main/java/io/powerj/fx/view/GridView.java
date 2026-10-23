package io.powerj.fx.view;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;

import io.powerj.fx.Messages;
import io.powerj.fx.model.CellFormatter;
import io.powerj.fx.model.ColumnSpec;
import io.powerj.fx.model.ColumnWidths;
import io.powerj.fx.model.GridModel;
import io.powerj.fx.model.GridRow;
import io.powerj.fx.model.GridTable;
import io.powerj.fx.model.SortDirection;
import io.powerj.fx.model.SortKey;
import io.powerj.fx.model.SortSpec;

/** The table and its status bar. Wiring only: the text of the cells and the widths come from the model. */
final class GridView {

    /** Rows read to size a column. */
    private static final int WIDTH_SAMPLE = 200;
    /** A character is about this wide, a column also has its padding. */
    private static final double CHAR_WIDTH = 7.5;
    private static final double PADDING = 28;

    private final BorderPane root = new BorderPane();

    GridView(GridModel model) {
        GridTable data = new GridTable(model);
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
        int count = model.rows().size();
        Label status = new Label(Messages.get(count == 1 ? "grid.rows.one" : "grid.rows", count));
        status.setPadding(new Insets(4, 8, 4, 8));
        root.setCenter(table);
        root.setBottom(status);
    }

    Parent root() {
        return root;
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

    private static TableColumn<GridRow, GridRow> column(ColumnSpec spec, GridModel model) {
        TableColumn<GridRow, GridRow> column = new TableColumn<>(spec.name());
        column.setId(spec.name());
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
}
