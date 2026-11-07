package com.sqlassist.dbeaver.format;

import java.util.Set;

/**
 * Words whose letter case the formatter may change. Deliberately limited to words that are
 * reserved in MySQL, PostgreSQL and SQL Server, so they can never be unquoted table names or
 * aliases (which are case-sensitive in some MySQL / SQL Server setups).
 */
final class Keywords {

    static final Set<String> RESERVED = Set.of(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "AS", "ON", "JOIN",
            "INNER", "LEFT", "RIGHT", "CROSS", "NATURAL", "OUTER", "USING", "GROUP", "BY", "ORDER",
            "HAVING", "LIMIT", "UNION", "INTERSECT", "EXCEPT", "ALL", "DISTINCT", "INSERT", "INTO",
            "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "REPLACE", "CASE", "WHEN", "THEN",
            "ELSE", "END", "BETWEEN", "LIKE", "EXISTS", "ASC", "DESC", "WITH", "RECURSIVE", "TRUE",
            "FALSE", "PRIMARY", "FOREIGN", "KEY", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT",
            "CONSTRAINT", "IF", "FETCH");

    /** Non-reserved words that are only re-cased where the formatter recognised them as keywords. */
    static final Set<String> CONTEXTUAL = Set.of(
            "OFFSET", "RETURNING", "WINDOW", "QUALIFY", "MINUS", "FULL", "APPLY", "CONFLICT", "DUPLICATE",
            "TOP", "VIEW", "TEMP", "TEMPORARY", "UNLOGGED", "DO", "NOTHING");

    private Keywords() {
    }

    static boolean mayChangeCase(String word) {
        String up = word.toUpperCase();
        return RESERVED.contains(up) || CONTEXTUAL.contains(up);
    }
}
