package com.sqlassist.dbeaver.validation;

import java.util.List;
import java.util.Optional;

/** Columns of one table in ordinal order. */
public record TableMeta(String name, List<ColumnMeta> columns) {

    /**
     * Finds a column by an identifier as written in SQL. Quoted identifiers
     * ("x", `x`, [x]) match exactly first; any name falls back to a case-insensitive match.
     */
    public Optional<ColumnMeta> find(String identifier) {
        String raw = identifier.strip();
        String name = unquote(raw);
        for (ColumnMeta c : columns) {
            if (c.name().equals(name)) {
                return Optional.of(c);
            }
        }
        for (ColumnMeta c : columns) {
            if (c.name().equalsIgnoreCase(name)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    static String unquote(String identifier) {
        if (identifier.length() >= 2) {
            char first = identifier.charAt(0);
            char last = identifier.charAt(identifier.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
                String q = String.valueOf(first);
                return identifier.substring(1, identifier.length() - 1).replace(q + q, q);
            }
            if (first == '[' && last == ']') {
                return identifier.substring(1, identifier.length() - 1).replace("]]", "]");
            }
        }
        return identifier;
    }
}
