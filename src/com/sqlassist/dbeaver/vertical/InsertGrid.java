package com.sqlassist.dbeaver.vertical;

import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.Span;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Module 2: editable, transposed model of an INSERT statement.
 * <pre>
 *   column | row 1      | row 2
 *   id     | 1          | 2
 *   name   | 'somename' | 'x'
 * </pre>
 * Cells hold raw SQL text (quotes, functions, NULL, ...), so editing is lossless.
 * Only cells that changed produce {@link TextReplacement}s; untouched formatting,
 * comments and whitespace in the original statement are preserved.
 * No UI dependencies, so it can be unit-tested.
 */
public final class InsertGrid {

    /** One editable piece of the statement: a column name or a value. */
    public static final class Slot {
        private final Span span;
        private final String original;
        private String current;

        Slot(Span span, String original) {
            this.span = span;
            this.original = original;
            this.current = original;
        }

        public Span span() {
            return span;
        }

        public String original() {
            return original;
        }

        public String current() {
            return current;
        }

        public boolean isModified() {
            return !current.equals(original);
        }

        public boolean isMultiLine() {
            return current.indexOf('\n') >= 0 || current.indexOf('\r') >= 0;
        }
    }

    private final String tableName;
    private final InsertStatement.SourceKind sourceKind;
    private final List<Slot> columns;
    private final List<List<Slot>> rows;
    private final int width;

    private InsertGrid(String tableName, InsertStatement.SourceKind sourceKind,
                       List<Slot> columns, List<List<Slot>> rows) {
        this.tableName = tableName;
        this.sourceKind = sourceKind;
        this.columns = columns;
        this.rows = rows;
        int w = columns.size();
        for (List<Slot> row : rows) {
            w = Math.max(w, row.size());
        }
        this.width = w;
    }

    public static InsertGrid of(InsertStatement statement, CharSequence text) {
        List<Slot> columns = new ArrayList<>();
        for (Span s : statement.columns()) {
            columns.add(new Slot(s, s.text(text)));
        }
        List<List<Slot>> rows = new ArrayList<>();
        for (List<Span> row : statement.rows()) {
            List<Slot> slots = new ArrayList<>();
            for (Span s : row) {
                slots.add(new Slot(s, s.text(text)));
            }
            rows.add(slots);
        }
        return new InsertGrid(statement.tableName(), statement.sourceKind(), columns, rows);
    }

    public String tableName() {
        return tableName;
    }

    public InsertStatement.SourceKind sourceKind() {
        return sourceKind;
    }

    /** Number of grid lines: max(column count, longest tuple). */
    public int width() {
        return width;
    }

    public int rowCount() {
        return rows.size();
    }

    public boolean hasColumnList() {
        return !columns.isEmpty();
    }

    /** Column name slot, or {@code null} when the tuple has more values than there are columns. */
    public Slot column(int index) {
        return index < columns.size() ? columns.get(index) : null;
    }

    /** Value slot, or {@code null} when this tuple is shorter than {@code index + 1}. */
    public Slot value(int row, int index) {
        List<Slot> r = rows.get(row);
        return index < r.size() ? r.get(index) : null;
    }

    /** Label for a grid line: the column text, or a placeholder for unnamed positions. */
    public String label(int index) {
        Slot c = column(index);
        if (c != null) {
            return c.current();
        }
        return hasColumnList() ? "(extra value " + (index + 1) + ")" : "(column " + (index + 1) + ")";
    }

    public void setColumn(int index, String text) {
        Slot c = column(index);
        if (c == null) {
            throw new IllegalArgumentException("No column at index " + index);
        }
        c.current = text;
    }

    public void setValue(int row, int index, String text) {
        Slot v = value(row, index);
        if (v == null) {
            throw new IllegalArgumentException("No value at row " + row + ", index " + index);
        }
        v.current = text;
    }

    public boolean isModified() {
        return allSlots().stream().anyMatch(Slot::isModified);
    }

    /**
     * Edits for every modified slot, ordered from the end of the document to the
     * start so that applying them one by one never shifts a pending offset.
     */
    public List<TextReplacement> replacements(CharSequence text) {
        List<TextReplacement> result = new ArrayList<>();
        for (Slot s : allSlots()) {
            if (!s.isModified()) {
                continue;
            }
            String replacement = s.current().strip();
            // An empty slot such as "(1, , 3)" starts right after the comma: keep a space.
            if (s.span().length() == 0 && !replacement.isEmpty() && s.span().start() > 0
                    && text.charAt(s.span().start() - 1) == ',') {
                replacement = " " + replacement;
            }
            result.add(new TextReplacement(s.span().start(), s.span().length(), replacement));
        }
        result.sort(Comparator.comparingInt(TextReplacement::offset).reversed());
        return result;
    }

    /** Applies {@link #replacements} to a copy of {@code text}; used by tests and previews. */
    public String applyTo(String text) {
        StringBuilder sb = new StringBuilder(text);
        for (TextReplacement r : replacements(text)) {
            sb.replace(r.offset(), r.offset() + r.length(), r.text());
        }
        return sb.toString();
    }

    private List<Slot> allSlots() {
        List<Slot> all = new ArrayList<>(columns);
        rows.forEach(all::addAll);
        return all;
    }
}
