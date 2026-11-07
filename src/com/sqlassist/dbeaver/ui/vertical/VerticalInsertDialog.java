package com.sqlassist.dbeaver.ui.vertical;

import com.sqlassist.dbeaver.ui.values.ValueEditorDialog;
import com.sqlassist.dbeaver.values.StringLiteral;
import com.sqlassist.dbeaver.values.ValueKind;
import com.sqlassist.dbeaver.vertical.InsertGrid;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerEditor;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationEvent;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationStrategy;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.FocusCellOwnerDrawHighlighter;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TableViewerEditor;
import org.eclipse.jface.viewers.TableViewerFocusCellManager;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.FrameworkUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Module 2: shows an INSERT transposed (one line per column, one grid column per
 * VALUES tuple) and lets the user edit names and values in place.
 * Edits stay in the {@link InsertGrid}; the caller writes them back on OK.
 */
public final class VerticalInsertDialog extends Dialog {

    /** Tuples shown side by side; longer multi-row INSERTs get a row spinner. */
    private static final int MAX_VISIBLE_ROWS = 8;

    private final InsertGrid grid;
    private final int initialLine;
    private int firstRow;

    private TableViewer viewer;
    private Composite tableHolder;
    private TableColumnLayout tableLayout;
    private final List<TableViewerColumn> rowColumns = new ArrayList<>();
    private Label status;
    private TableViewerFocusCellManager focusManager;
    private String filter = "";

    /**
     * @param initialLine grid line (column index) to select first, or -1
     * @param initialRow  tuple to scroll to, or -1
     */
    public VerticalInsertDialog(Shell parent, InsertGrid grid, int initialLine, int initialRow) {
        super(parent);
        this.grid = grid;
        this.initialLine = initialLine;
        int row = Math.max(0, initialRow);
        this.firstRow = Math.max(0, Math.min(row, grid.rowCount() - MAX_VISIBLE_ROWS));
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Vertical INSERT - " + grid.tableName());
    }

    @Override
    protected Point getInitialSize() {
        return new Point(760, 520);
    }

    @Override
    protected IDialogSettings getDialogBoundsSettings() {
        IDialogSettings root = PlatformUI.getDialogSettingsProvider(FrameworkUtil.getBundle(getClass()))
                .getDialogSettings();
        IDialogSettings section = root.getSection("VerticalInsertDialog");
        return section != null ? section : root.addNewSection("VerticalInsertDialog");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        GridLayoutFactory.swtDefaults().numColumns(4).applyTo(area);

        Label summary = new Label(area, SWT.NONE);
        summary.setText(summaryText());
        GridDataFactory.fillDefaults().grab(true, false).applyTo(summary);

        Text search = new Text(area, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        search.setMessage("Filter columns or values");
        GridDataFactory.fillDefaults().hint(220, SWT.DEFAULT).applyTo(search);
        search.addModifyListener(e -> {
            filter = search.getText().trim().toLowerCase(Locale.ROOT);
            viewer.refresh();
        });

        if (grid.rowCount() > MAX_VISIBLE_ROWS) {
            Label rowLabel = new Label(area, SWT.NONE);
            rowLabel.setText("First row:");
            Spinner spinner = new Spinner(area, SWT.BORDER);
            spinner.setValues(firstRow + 1, 1, grid.rowCount() - MAX_VISIBLE_ROWS + 1, 0, 1, MAX_VISIBLE_ROWS);
            spinner.addModifyListener(e -> {
                firstRow = spinner.getSelection() - 1;
                rebuildRowColumns();
            });
        } else {
            GridDataFactory.fillDefaults().span(2, 1).applyTo(new Label(area, SWT.NONE));
        }

        tableHolder = new Composite(area, SWT.NONE);
        tableLayout = new TableColumnLayout();
        tableHolder.setLayout(tableLayout);
        GridDataFactory.fillDefaults().grab(true, true).span(4, 1).applyTo(tableHolder);

        viewer = new TableViewer(tableHolder, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.addFilter(new LineFilter());
        installKeyboardEditing();

        createIndexColumn();
        createNameColumn();
        rebuildRowColumns();
        viewer.setInput(IntStream.range(0, grid.width()).boxed().toList());

        status = new Label(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).span(3, 1).applyTo(status);
        Button valueEditor = new Button(area, SWT.PUSH);
        valueEditor.setText("Open in Value Editor...");
        valueEditor.setToolTipText("Edit the selected string value as JSON, XML or multi-line text");
        valueEditor.addListener(SWT.Selection, e -> openValueEditor());
        viewer.getTable().addListener(SWT.MouseDoubleClick, e -> {
            InsertGrid.Slot slot = focusedValue().map(f -> grid.value(f[0], f[1])).orElse(null);
            if (slot != null && slot.isMultiLine()) {
                openValueEditor(); // multi-line values are not editable in a table cell
            }
        });
        updateStatus();

        if (initialLine >= 0 && initialLine < grid.width()) {
            viewer.setSelection(new StructuredSelection(initialLine), true);
        }
        viewer.getTable().setFocus();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Apply to Editor", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    // ------------------------------------------------------------------ columns

    private void createIndexColumn() {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.RIGHT);
        col.getColumn().setText("#");
        col.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return Integer.toString((Integer) element + 1);
            }

            @Override
            public Color getForeground(Object element) {
                return dimColor();
            }
        });
        tableLayout.setColumnData(col.getColumn(), new ColumnWeightData(0, 40, false));
    }

    private void createNameColumn() {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.LEFT);
        col.getColumn().setText("Column");
        col.setLabelProvider(new SlotLabelProvider(-1));
        col.setEditingSupport(new SlotEditingSupport(-1));
        tableLayout.setColumnData(col.getColumn(), new ColumnWeightData(30, 120, true));
    }

    private void rebuildRowColumns() {
        viewer.getTable().setRedraw(false);
        try {
            rowColumns.forEach(c -> c.getColumn().dispose());
            rowColumns.clear();
            int last = Math.min(grid.rowCount(), firstRow + MAX_VISIBLE_ROWS);
            for (int r = firstRow; r < last; r++) {
                TableViewerColumn col = new TableViewerColumn(viewer, SWT.LEFT);
                TableColumn column = col.getColumn();
                column.setText(rowHeader(r));
                col.setLabelProvider(new SlotLabelProvider(r));
                SlotEditingSupport editing = new SlotEditingSupport(r);
                col.setEditingSupport(editing);
                column.addDisposeListener(e -> editing.dispose());
                tableLayout.setColumnData(column, new ColumnWeightData(70 / Math.max(1, last - firstRow) + 10, 120, true));
                rowColumns.add(col);
            }
            tableHolder.layout(true, true);
            viewer.refresh();
        } finally {
            viewer.getTable().setRedraw(true);
        }
    }

    private String rowHeader(int row) {
        return switch (grid.sourceKind()) {
            case SELECT -> "SELECT expression";
            default -> grid.rowCount() == 1 ? "Value" : "Row " + (row + 1);
        };
    }

    private void installKeyboardEditing() {
        TableViewerFocusCellManager focus = new TableViewerFocusCellManager(viewer, new FocusCellOwnerDrawHighlighter(viewer));
        focusManager = focus;
        ColumnViewerEditorActivationStrategy activation = new ColumnViewerEditorActivationStrategy(viewer) {
            @Override
            protected boolean isEditorActivationEvent(ColumnViewerEditorActivationEvent event) {
                return event.eventType == ColumnViewerEditorActivationEvent.MOUSE_DOUBLE_CLICK_SELECTION
                        || event.eventType == ColumnViewerEditorActivationEvent.PROGRAMMATIC
                        || (event.eventType == ColumnViewerEditorActivationEvent.KEY_PRESSED
                            && (event.keyCode == SWT.F2 || event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR));
            }
        };
        TableViewerEditor.create(viewer, focus, activation, ColumnViewerEditor.TABBING_HORIZONTAL
                | ColumnViewerEditor.TABBING_MOVE_TO_ROW_NEIGHBOR | ColumnViewerEditor.TABBING_VERTICAL
                | ColumnViewerEditor.KEYBOARD_ACTIVATION);
    }

    // ------------------------------------------------------------------ value editor (Module 5)

    /** {row, line} of the focused value cell, if a value (not the name/index column) is focused. */
    private Optional<int[]> focusedValue() {
        ViewerCell cell = focusManager == null ? null : focusManager.getFocusCell();
        if (cell == null || cell.getColumnIndex() < 2) {
            return Optional.empty();
        }
        return Optional.of(new int[] {firstRow + cell.getColumnIndex() - 2, (Integer) cell.getElement()});
    }

    private void openValueEditor() {
        Optional<int[]> focused = focusedValue();
        InsertGrid.Slot slot = focused.map(f -> grid.value(f[0], f[1])).orElse(null);
        if (slot == null) {
            MessageDialog.openInformation(getShell(), "Value Editor", "Select a value cell first.");
            return;
        }
        Optional<StringLiteral> literal = StringLiteral.parse(slot.current());
        if (literal.isEmpty()) {
            MessageDialog.openInformation(getShell(), "Value Editor",
                    "Only string values ('...') can be opened in the value editor.");
            return;
        }
        int row = focused.get()[0];
        int line = focused.get()[1];
        String content = literal.get().content();
        ValueEditorDialog dialog = new ValueEditorDialog(getShell(), "Value Editor - " + grid.label(line),
                content, ValueKind.detect(content), true);
        if (dialog.open() == OK && dialog.result() != null && !dialog.result().equals(content)) {
            grid.setValue(row, line, literal.get().render(dialog.result()));
            viewer.update(line, null);
            updateStatus();
        }
    }

    // ------------------------------------------------------------------ cell model

    /** row == -1 means the column-name cell. */
    private InsertGrid.Slot slot(int line, int row) {
        return row < 0 ? grid.column(line) : grid.value(row, line);
    }

    private String summaryText() {
        String rows = grid.rowCount() == 1 ? "1 row" : grid.rowCount() + " rows";
        String text = grid.tableName() + ": " + grid.width() + " columns, " + rows;
        if (!grid.hasColumnList()) {
            text += " (no column list: names unknown)";
        }
        return text;
    }

    private void updateStatus() {
        long changed = IntStream.range(0, grid.width()).mapToLong(line -> {
            long n = 0;
            for (int r = -1; r < grid.rowCount(); r++) {
                InsertGrid.Slot s = slot(line, r);
                if (s != null && s.isModified()) {
                    n++;
                }
            }
            return n;
        }).sum();
        status.setText(changed == 0
                ? "Double-click, Enter or F2 to edit. Values are raw SQL: keep quotes for strings."
                : changed + (changed == 1 ? " change" : " changes") + " will be written back to the editor.");
        Button ok = getButton(IDialogConstants.OK_ID);
        if (ok != null) {
            ok.setEnabled(changed > 0);
        }
    }

    @Override
    protected Control createButtonBar(Composite parent) {
        Control bar = super.createButtonBar(parent);
        updateStatus();
        return bar;
    }

    private Color dimColor() {
        return viewer.getTable().getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
    }

    private final class SlotLabelProvider extends ColumnLabelProvider {
        private final int row;

        SlotLabelProvider(int row) {
            this.row = row;
        }

        @Override
        public String getText(Object element) {
            int line = (Integer) element;
            if (row < 0) {
                return grid.label(line);
            }
            InsertGrid.Slot s = slot(line, row);
            if (s == null) {
                return "(missing)";
            }
            return s.current().replace("\r\n", "\n").replace("\n", " ⏎ ");
        }

        @Override
        public Font getFont(Object element) {
            InsertGrid.Slot s = slot((Integer) element, row);
            return s != null && s.isModified() ? JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT) : null;
        }

        @Override
        public Color getForeground(Object element) {
            InsertGrid.Slot s = slot((Integer) element, row);
            return s == null ? dimColor() : null;
        }

        @Override
        public String getToolTipText(Object element) {
            InsertGrid.Slot s = slot((Integer) element, row);
            if (s == null) {
                return row < 0 ? "This tuple has more values than the column list" : "This tuple has no value here";
            }
            if (s.isMultiLine()) {
                return "Multi-line value: double-click to open the value editor";
            }
            return s.isModified() ? "Was: " + s.original() : null;
        }
    }

    private final class SlotEditingSupport extends EditingSupport {
        private final int row;
        private final TextCellEditor editor;

        SlotEditingSupport(int row) {
            super(viewer);
            this.row = row;
            this.editor = new TextCellEditor(viewer.getTable());
        }

        void dispose() {
            editor.dispose();
        }

        @Override
        protected boolean canEdit(Object element) {
            InsertGrid.Slot s = slot((Integer) element, row);
            return s != null && !s.isMultiLine();
        }

        @Override
        protected CellEditor getCellEditor(Object element) {
            return editor;
        }

        @Override
        protected Object getValue(Object element) {
            return slot((Integer) element, row).current();
        }

        @Override
        protected void setValue(Object element, Object value) {
            int line = (Integer) element;
            String text = String.valueOf(value);
            if (row < 0) {
                grid.setColumn(line, text);
            } else {
                grid.setValue(row, line, text);
            }
            viewer.update(element, null);
            updateStatus();
        }
    }

    private final class LineFilter extends ViewerFilter {
        @Override
        public boolean select(Viewer v, Object parentElement, Object element) {
            if (filter.isEmpty()) {
                return true;
            }
            int line = (Integer) element;
            if (grid.label(line).toLowerCase(Locale.ROOT).contains(filter)) {
                return true;
            }
            for (int r = 0; r < grid.rowCount(); r++) {
                InsertGrid.Slot s = grid.value(r, line);
                if (s != null && s.current().toLowerCase(Locale.ROOT).contains(filter)) {
                    return true;
                }
            }
            return false;
        }
    }
}
