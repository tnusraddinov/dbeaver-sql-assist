package com.sqlassist.dbeaver.validation;

import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.InsertStatementParser;
import com.sqlassist.dbeaver.validation.ColumnMeta.Kind;
import com.sqlassist.dbeaver.validation.Problem.Severity;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free tests for Module 3 rules (run by build.sh). */
public final class InsertValidatorTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private static final TableMeta USERS = new TableMeta("users", List.of(
            new ColumnMeta("id", "serial", Kind.INTEGER, -1, true, true, true),
            new ColumnMeta("name", "varchar", Kind.STRING, 5, true, false, false),
            new ColumnMeta("age", "int4", Kind.INTEGER, -1, false, false, false),
            new ColumnMeta("score", "numeric", Kind.DECIMAL, -1, false, false, false),
            new ColumnMeta("active", "bool", Kind.BOOLEAN, -1, true, true, false),
            new ColumnMeta("born", "date", Kind.DATETIME, -1, false, false, false),
            new ColumnMeta("Note", "text", Kind.STRING, -1, false, false, false)));

    public static void main(String[] args) {
        // Clean statement: no problems.
        expect("INSERT INTO users (name, age) VALUES ('abc', 25)");
        expect("INSERT INTO users (name, age, score) VALUES ('abc', NULL, -1.5e3)");
        expect("INSERT INTO users (name, age) VALUES (upper('much too long'), $1)"); // expressions are trusted
        expect("INSERT INTO users (name, born, active) VALUES ('a', '2024-01-31', 'yes')");
        expect("INSERT INTO users (id, name) VALUES (DEFAULT, 'a')");
        expect("INSERT INTO users (NAME, \"Note\") VALUES ('a', 'x')"); // case-insensitive / quoted

        // Type mismatches.
        expect("INSERT INTO users (name, age) VALUES ('a', 'abc')", "ERROR 'abc' is not a valid integer");
        expect("INSERT INTO users (name, age) VALUES ('a', '25')", "WARNING String literal for age");
        expect("INSERT INTO users (name, age) VALUES ('a', 2.5)", "WARNING 2.5 has a fractional part");
        expect("INSERT INTO users (name, score) VALUES ('a', 'x1')", "ERROR 'x1' is not a valid number");
        expect("INSERT INTO users (name, active) VALUES ('a', 'maybe')", "ERROR 'maybe' is not a valid boolean");
        expect("INSERT INTO users (name, born) VALUES ('a', 'yesterday')", "WARNING 'yesterday' does not look like a date");

        // NOT NULL.
        expect("INSERT INTO users (name, age) VALUES (NULL, 1)", "ERROR Column name varchar(5) is NOT NULL");
        expect("INSERT INTO users (age) VALUES (1)", "WARNING NOT NULL column without default is missing: name");
        expect("INSERT INTO users (id, name) VALUES (NULL, 'a')"); // auto-generated id accepts NULL

        // Length.
        expect("INSERT INTO users (name) VALUES ('abcdef')", "ERROR Value is 6 characters; name varchar(5) allows 5");
        expect("INSERT INTO users (name) VALUES ('it''s')"); // 4 chars after unescaping
        expect("INSERT INTO users (name) VALUES (123456)", "ERROR Value is 6 characters");

        // Columns and arity.
        expect("INSERT INTO users (name, nope) VALUES ('a', 1)", "ERROR Column nope does not exist in users");
        expect("INSERT INTO users (name, name) VALUES ('a', 'b')", "ERROR Column name is listed twice");
        expect("INSERT INTO users (name, age) VALUES ('a', 1, 2)", "ERROR No column for this value");
        expect("INSERT INTO users (name, age) VALUES ('a')", "ERROR VALUES has 1 values but 2 columns are listed");
        expect("INSERT INTO users (name, age) VALUES ('a', 1), ('b')", "ERROR Row 2 has 1 values but 2 columns");

        // No column list: positional mapping to table columns.
        expect("INSERT INTO users VALUES (1, 'abc', 'x', 1, true, '2020-01-01', 'n')",
                "ERROR 'x' is not a valid integer");
        expect("INSERT INTO users VALUES (1, 'abc')", "ERROR VALUES has 2 values but 7 columns exist in users");

        // Problem positions point at the offending value.
        String sql = "INSERT INTO users (name, age) VALUES ('a', 'abc')";
        Problem p = problems(sql).get(0);
        check("span on value", sql.substring(p.span().start(), p.span().end()).equals("'abc'"), p.toString());

        // Literal classification.
        check("literal N string", Literal.of("N'x'").kind() == Literal.Kind.STRING, "");
        check("literal negative", Literal.of("-5").kind() == Literal.Kind.NUMBER, "");
        check("literal cast is expression", Literal.of("'5'::int").kind() == Literal.Kind.EXPRESSION, "");
        check("literal dollar", Literal.of("$$a'b$$").value().equals("a'b"), Literal.of("$$a'b$$").value());
        check("literal hex", Literal.of("X'FF'").kind() == Literal.Kind.EXPRESSION, "");

        System.out.println("Validation tests: " + passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static List<Problem> problems(String sql) {
        InsertStatement s = InsertStatementParser.parseAll(sql).get(0);
        return InsertValidator.validate(s, sql, USERS);
    }

    /** Each expectation is "SEVERITY message-prefix"; the problem list must match one-to-one. */
    private static void expect(String sql, String... expected) {
        List<Problem> actual = problems(sql);
        boolean ok = actual.size() == expected.length;
        for (int i = 0; ok && i < expected.length; i++) {
            String[] parts = expected[i].split(" ", 2);
            Problem p = actual.get(i);
            ok = p.severity() == Severity.valueOf(parts[0]) && p.message().startsWith(parts[1]);
        }
        check(sql, ok, "expected " + List.of(expected) + ", got "
                + actual.stream().map(p -> p.severity() + " " + p.message()).toList());
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add(name + " -> " + detail);
        }
    }
}
