package com.sqlassist.dbeaver.parser;

import java.util.List;

/**
 * Result of a caret lookup inside an INSERT.
 *
 * @param kind        whether the caret is on a column name or on a value
 * @param columnIndex zero-based position inside the column list / tuple
 * @param rowIndex    VALUES tuple index (-1 when the caret is on a column)
 * @param source      span under the caret
 * @param targets     matching spans on the other side (empty when the tuple has
 *                    more items than there are columns)
 */
public record CrossReference(Kind kind, int columnIndex, int rowIndex, Span source, List<Span> targets) {

    public enum Kind { COLUMN, VALUE }
}
