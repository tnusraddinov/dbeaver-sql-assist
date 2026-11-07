package com.sqlassist.dbeaver.hints;

import com.sqlassist.dbeaver.parser.InsertStatementParser;
import com.sqlassist.dbeaver.validation.ColumnMeta;
import com.sqlassist.dbeaver.validation.ColumnMeta.Kind;
import com.sqlassist.dbeaver.validation.TableMeta;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free tests for Module 4 (run by build.sh). */
public final class ColumnHintsTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private static final TableMeta USERS = new TableMeta("users", List.of(
            new ColumnMeta("id", "int4", Kind.INTEGER, -1, true, true, true),
            new ColumnMeta("name", "varchar", Kind.STRING, 50, true, false, false),
            new ColumnMeta("age", "int4", Kind.INTEGER, -1, false, false, false)));

    public static void main(String[] args) {
        // Without metadata: names only, rendered as "<value-with-hints>".
        expect("INSERT INTO users (id, name, age) VALUES (1, 'somename', 25)", null, true,
                "INSERT INTO users (id, name, age) VALUES (id: 1, name: 'somename', age: 25)");
        // With metadata: names and types.
        expect("INSERT INTO users (id, name) VALUES (1, 'x'), (2, 'y')", USERS, true,
                "INSERT INTO users (id, name) VALUES (id (int4): 1, name (varchar(50)): 'x'), "
                        + "(id (int4): 2, name (varchar(50)): 'y')");
        // Types can be switched off.
        expect("INSERT INTO users (id, name) VALUES (1, 'x')", USERS, false,
                "INSERT INTO users (id, name) VALUES (id: 1, name: 'x')");
        // No column list: names from metadata, nothing without it.
        expect("INSERT INTO users VALUES (1, 'x', 3)", USERS, false,
                "INSERT INTO users VALUES (id: 1, name: 'x', age: 3)");
        expect("INSERT INTO users VALUES (1, 'x', 3)", null, true, "INSERT INTO users VALUES (1, 'x', 3)");
        // Single column: no hints.
        expect("INSERT INTO users (name) VALUES ('x')", USERS, true, "INSERT INTO users (name) VALUES ('x')");
        // Self-describing values and extra values get no hint; empty slots are skipped.
        expect("INSERT INTO users (id, name, age) VALUES (:id, @name, 3, 4)", null, true,
                "INSERT INTO users (id, name, age) VALUES (:id, @name, age: 3, 4)");
        expect("INSERT INTO users (id, name) SELECT u.id, upper(n) FROM u", null, true,
                "INSERT INTO users (id, name) SELECT u.id, name: upper(n) FROM u");
        expect("INSERT INTO users (id, name, age) VALUES (1, , 3)", null, true,
                "INSERT INTO users (id, name, age) VALUES (id: 1, , age: 3)");
        // Quoted column names: hint shows the name as written, metadata still matches.
        expect("INSERT INTO users (\"id\", \"name\") VALUES (1, 'x')", USERS, true,
                "INSERT INTO users (\"id\", \"name\") VALUES (\"id\" (int4): 1, \"name\" (varchar(50)): 'x')");

        System.out.println("Column hint tests: " + passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** Renders hints inline so expectations read like what the user sees. */
    private static void expect(String sql, TableMeta table, boolean types, String expected) {
        List<ColumnHints.Hint> hints = ColumnHints.compute(InsertStatementParser.parseAll(sql).get(0), sql, table, types);
        StringBuilder sb = new StringBuilder(sql);
        for (int i = hints.size() - 1; i >= 0; i--) {
            sb.insert(hints.get(i).offset(), hints.get(i).label());
        }
        String actual = sb.toString();
        if (actual.equals(expected)) {
            passed++;
        } else {
            failures.add(sql + "\n    expected: " + expected + "\n    actual:   " + actual);
        }
    }
}
