package com.sqlassist.dbeaver.hints;

import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.Span;
import com.sqlassist.dbeaver.validation.ColumnMeta;
import com.sqlassist.dbeaver.validation.TableMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Module 4: computes the inline "ghost text" shown before each VALUES item:
 * <pre>VALUES (id: 1, name (varchar(50)): 'somename')</pre>
 * Column names come from the INSERT's column list or, without one, from table metadata.
 * Types are added when metadata is available. No UI dependencies.
 */
public final class ColumnHints {

    /** Ghost text to draw just before {@code offset}. */
    public record Hint(int offset, String label) { }

    private ColumnHints() {
    }

    /**
     * @param table metadata, or {@code null} when not connected / table unknown
     */
    public static List<Hint> compute(InsertStatement statement, CharSequence text, TableMeta table, boolean showTypes) {
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        if (statement.hasColumnList()) {
            for (Span column : statement.columns()) {
                String written = column.text(text).strip();
                names.add(written);
                Optional<ColumnMeta> meta = table == null ? Optional.empty() : table.find(written);
                types.add(meta.map(ColumnMeta::typeLabel).orElse(null));
            }
        } else if (table != null) {
            for (ColumnMeta c : table.columns()) {
                names.add(c.name());
                types.add(c.typeLabel());
            }
        }
        if (names.size() < 2) {
            return List.of(); // a single column needs no reminder
        }
        List<Hint> hints = new ArrayList<>();
        for (List<Span> row : statement.rows()) {
            for (int i = 0; i < row.size() && i < names.size(); i++) {
                Span value = row.get(i);
                String name = names.get(i);
                if (value.length() == 0 || name.isEmpty() || isSelfDescribing(value.text(text), name)) {
                    continue;
                }
                String type = showTypes ? types.get(i) : null;
                hints.add(new Hint(value.start(), type == null ? name + ": " : name + " (" + type + "): "));
            }
        }
        return hints;
    }

    /** {@code :name}, {@code @name}, {@code name} or {@code t.name} for column {@code name} needs no hint. */
    static boolean isSelfDescribing(String value, String column) {
        String v = value.strip();
        if (v.startsWith(":") || v.startsWith("@")) {
            v = v.substring(1);
        }
        int dot = v.lastIndexOf('.');
        if (dot >= 0) {
            v = v.substring(dot + 1);
        }
        return unquote(v).equalsIgnoreCase(unquote(column.strip()));
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '`' || s.charAt(0) == '[')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
