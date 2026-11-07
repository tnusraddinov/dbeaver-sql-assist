package com.sqlassist.dbeaver.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Structural view of one INSERT statement:
 * <pre>
 * INSERT INTO table (c0, c1, c2) VALUES (v00, v01, v02), (v10, v11, v12)
 * INSERT INTO table (c0, c1)     SELECT e0, e1 FROM ...
 * </pre>
 * {@link #rows()} holds one list per VALUES tuple (or a single list with the SELECT items).
 * All positions are absolute document offsets.
 */
public record InsertStatement(
        Span range,
        String tableName,
        Span tableNameSpan,
        List<Span> columns,
        List<List<Span>> rows,
        SourceKind sourceKind) {

    public enum SourceKind { VALUES, SELECT, NONE }

    public boolean hasColumnList() {
        return !columns.isEmpty();
    }

    /** True when a VALUES tuple has a different number of items than the column list. */
    public boolean isArityMismatch(int rowIndex) {
        return hasColumnList() && rows.get(rowIndex).size() != columns.size();
    }

    /**
     * Resolves what the caret is on and which spans belong to it.
     * Column under caret -> the value at the same index in every row.
     * Value under caret  -> the column at the same index.
     */
    public Optional<CrossReference> crossReferenceAt(int offset) {
        if (!range.containsCaret(offset) || !hasColumnList()) {
            return Optional.empty();
        }
        for (int i = 0; i < columns.size(); i++) {
            Span column = columns.get(i);
            if (column.containsCaret(offset)) {
                List<Span> values = new ArrayList<>();
                for (List<Span> row : rows) {
                    if (i < row.size()) {
                        values.add(row.get(i));
                    }
                }
                return Optional.of(new CrossReference(CrossReference.Kind.COLUMN, i, -1, column, values));
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            List<Span> row = rows.get(r);
            for (int i = 0; i < row.size(); i++) {
                Span value = row.get(i);
                if (value.containsCaret(offset)) {
                    List<Span> targets = i < columns.size() ? List.of(columns.get(i)) : List.of();
                    return Optional.of(new CrossReference(CrossReference.Kind.VALUE, i, r, value, targets));
                }
            }
        }
        return Optional.empty();
    }
}
