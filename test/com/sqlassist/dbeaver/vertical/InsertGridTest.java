package com.sqlassist.dbeaver.vertical;

import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.InsertStatementParser;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free tests for the vertical INSERT model (run by build.sh). */
public final class InsertGridTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        String sql = """
                -- header
                INSERT INTO users (id, name, age)
                VALUES (1, 'somename', 25), /* keep */ (2, 'x', 30);
                SELECT 1;
                """;
        InsertGrid grid = grid(sql);
        check("width", grid.width() == 3, "" + grid.width());
        check("rows", grid.rowCount() == 2, "" + grid.rowCount());
        check("label", grid.label(1).equals("name"), grid.label(1));
        check("value", grid.value(1, 1).current().equals("'x'"), grid.value(1, 1).current());
        check("unmodified", !grid.isModified() && grid.applyTo(sql).equals(sql), "");

        grid.setValue(0, 1, "'a much longer name'");
        grid.setValue(1, 2, "NULL");
        grid.setColumn(0, "user_id");
        String expected = sql.replace("(id,", "(user_id,").replace("'somename'", "'a much longer name'")
                .replace("'x', 30)", "'x', NULL)");
        equal("edits keep layout and comments", expected, grid.applyTo(sql));
        check("modified", grid.isModified(), "");
        List<TextReplacement> reps = grid.replacements(sql);
        check("descending order", reps.size() == 3 && reps.get(0).offset() > reps.get(1).offset()
                && reps.get(1).offset() > reps.get(2).offset(), reps.toString());

        // Reverting a value produces no edit.
        grid.setValue(1, 2, "30");
        check("revert removes edit", grid.replacements(sql).size() == 2, "");

        // Value is trimmed; empty slot gets a space after the comma.
        String gap = "INSERT INTO t (a, b, c) VALUES (1, , 3)";
        InsertGrid g2 = grid(gap);
        g2.setValue(0, 1, "  'b'  ");
        equal("fill empty slot", "INSERT INTO t (a, b, c) VALUES (1, 'b' , 3)", g2.applyTo(gap));

        // Arity mismatch: extra value line and a missing cell.
        String mismatch = "INSERT INTO t (a, b) VALUES (1, 2, 3), (4)";
        InsertGrid g3 = grid(mismatch);
        check("mismatch width", g3.width() == 3, "" + g3.width());
        check("extra label", g3.label(2).equals("(extra value 3)"), g3.label(2));
        check("missing cell", g3.value(1, 1) == null, "");

        // No column list: placeholders, values still editable.
        String noCols = "INSERT INTO t VALUES (1, 'x')";
        InsertGrid g4 = grid(noCols);
        check("placeholder label", g4.label(1).equals("(column 2)"), g4.label(1));
        g4.setValue(0, 1, "'y'");
        equal("edit without columns", "INSERT INTO t VALUES (1, 'y')", g4.applyTo(noCols));

        // INSERT ... SELECT.
        String select = "INSERT INTO t (a, b) SELECT x, upper(y) FROM s";
        InsertGrid g5 = grid(select);
        g5.setValue(0, 1, "lower(y)");
        equal("edit select item", "INSERT INTO t (a, b) SELECT x, lower(y) FROM s", g5.applyTo(select));

        System.out.println("Vertical grid tests: " + passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static InsertGrid grid(String sql) {
        InsertStatement s = InsertStatementParser.parseAll(sql).get(0);
        return InsertGrid.of(s, sql);
    }

    private static void equal(String name, String expected, String actual) {
        check(name, expected.equals(actual), "\n    expected: " + expected + "\n    actual:   " + actual);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add(name + " -> " + detail);
        }
    }
}
