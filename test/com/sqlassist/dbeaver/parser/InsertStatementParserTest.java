package com.sqlassist.dbeaver.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dependency-free tests for the INSERT parser (run by build.sh).
 * Convention: '|' in the SQL marks the caret and is removed before parsing.
 */
public final class InsertStatementParserTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        columnToValue();
        valueToColumn();
        multiRowColumnHighlightsAllRows();
        nestedFunctionsAndStrings();
        commentsAndQuotedIdentifiers();
        insertSelect();
        noColumnList();
        caretOutsideItems();
        tooManyValues();
        emptyItems();
        multipleStatements();
        replaceFunctionIsNotStatement();
        mysqlRowAndModifiers();
        postgresDollarQuotesAndCasts();
        unterminatedWhileTyping();
        largeDocumentWindow();

        System.out.println("Parser tests: " + passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ cases

    static void columnToValue() {
        expectTargets("INSERT INTO users (id, na|me, age) VALUES (1, 'somename', 25)", "'somename'");
    }

    static void valueToColumn() {
        expectTargets("INSERT INTO users (id, name, age) VALUES (1, 'somename', 2|5)", "age");
    }

    static void multiRowColumnHighlightsAllRows() {
        expectTargets("INSERT INTO t (a, |b) VALUES (1, 'x'), (2, 'y'),\n (3, 'z')", "'x'", "'y'", "'z'");
        expectTargets("INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y'), (|3, 'z')", "a");
    }

    static void nestedFunctionsAndStrings() {
        String sql = "INSERT INTO t (a, b, c) VALUES (coalesce(x, 1), 'a, (b)', |now())";
        expectTargets(sql, "c");
        expectTargets("INSERT INTO t (a, |b, c) VALUES (coalesce(x, 1), 'a, (b)', now())", "'a, (b)'");
        expectTargets("INSERT INTO t (a, b, |c) VALUES (1, (SELECT max(id) FROM t2 WHERE x IN (1,2)), ARRAY[1,2])",
                "ARRAY[1,2]");
        expectTargets("INSERT INTO t (a, b) VALUES ('it''s', 'x|')", "b");
    }

    static void commentsAndQuotedIdentifiers() {
        String sql = """
                INSERT INTO "My Schema"."T" ( -- comment, with (parens)
                    "first, col", `b` /* , */, [c d]
                ) VALUES (1, 2, |3)
                """;
        expectTargets(sql, "[c d]");
        expectTargets(sql.replace("|", "").replace("`b`", "`|b`"), "2");
        Optional<InsertStatement> s = InsertStatementParser.findAt(sql.replace("|", ""), 5);
        check("quoted table name", s.isPresent() && s.get().tableName().equals("\"My Schema\".\"T\""),
                s.map(InsertStatement::tableName).orElse("<none>"));
    }

    static void insertSelect() {
        expectTargets("INSERT INTO t (a, |b) SELECT x, upper(y) AS yy FROM src WHERE z > 1", "upper(y) AS yy");
        expectTargets("INSERT INTO t (a, b) SELECT DISTINCT |x, y FROM src", "a");
        expectTargets("INSERT INTO t (a, b) OVERRIDING SYSTEM VALUE VALUES (1, |2)", "b");
    }

    static void noColumnList() {
        expectNone("INSERT INTO t VALUES (1, |2)");
        expectNone("INSERT INTO t (SELECT |1, 2)");
    }

    static void caretOutsideItems() {
        expectNone("INSERT INTO t (a, b) |VALUES (1, 2)");
        expectNone("SELECT a, |b FROM t");
        expectNone("INSERT INTO t (a, b) VALUES (1, 2) ON CONFLICT (a) DO UPDATE SET b = |3");
    }

    static void tooManyValues() {
        expectTargets("INSERT INTO t (a, b) VALUES (1, 2, |3)"); // no column -> no target
        InsertStatement s = InsertStatementParser.parseAll("INSERT INTO t (a, b) VALUES (1, 2, 3)").get(0);
        check("arity mismatch detected", s.isArityMismatch(0), "expected mismatch");
    }

    static void emptyItems() {
        // Indices stay aligned when the user left a slot empty.
        expectTargets("INSERT INTO t (a, b, c) VALUES (1, , |3)", "c");
        InsertStatement s = InsertStatementParser.parseAll("INSERT INTO t (a, b, c) VALUES (1, , 3)").get(0);
        check("empty item kept", s.rows().get(0).size() == 3, "size=" + s.rows().get(0).size());
    }

    static void multipleStatements() {
        String sql = """
                INSERT INTO a (x, y) VALUES (1, 2);
                UPDATE a SET x = 1;
                INSERT INTO b (p, q) VALUES ('p', |'q');
                """;
        expectTargets(sql, "q");
        check("two statements", InsertStatementParser.parseAll(sql.replace("|", "")).size() == 2, "");
    }

    static void replaceFunctionIsNotStatement() {
        expectTargets("INSERT INTO t (a, |b) VALUES (1, replace('x', 'y', 'z'))", "replace('x', 'y', 'z')");
        expectTargets("REPLACE INTO t (a, b) VALUES (1, |2)", "b");
    }

    static void mysqlRowAndModifiers() {
        expectTargets("INSERT IGNORE INTO t (a, b) VALUES ROW(1, 2), ROW(3, |4)", "b");
        expectTargets("INSERT OR REPLACE INTO t (a, b) VALUES (|1, 2)", "a");
        expectTargets("INSERT t (a, b) VALUES (1, |2)", "b"); // T-SQL without INTO
        expectTargets("INSERT INTO t WITH (TABLOCK) (a, b) VALUES (1, |2)", "b");
    }

    static void postgresDollarQuotesAndCasts() {
        expectTargets("INSERT INTO t (a, b) VALUES ($$x, (y)$$, '1'::int|)", "b");
        expectTargets("INSERT INTO t (a, |b) VALUES ($1, E'it\\'s'::text)", "E'it\\'s'::text");
        expectTargets("INSERT INTO t (a, b) VALUES (:first, |:second)", "b");
    }

    static void unterminatedWhileTyping() {
        expectTargets("INSERT INTO t (a, b, c) VALUES (1, 'x|", "b");
    }

    static void largeDocumentWindow() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < InsertStatementParser.LARGE_DOCUMENT_THRESHOLD + 10_000) {
            sb.append("INSERT INTO t (a, b) VALUES (1, 'filler');\n");
        }
        sb.append("INSERT INTO t (a, b) VALUES (1, 'last|');\n");
        expectTargets(sb.toString(), "b");
    }

    // ------------------------------------------------------------------ helpers

    private static void expectTargets(String sqlWithCaret, String... expected) {
        int caret = sqlWithCaret.indexOf('|');
        String sql = sqlWithCaret.substring(0, caret) + sqlWithCaret.substring(caret + 1);
        List<String> actual = InsertStatementParser.findAt(sql, caret)
                .flatMap(s -> s.crossReferenceAt(caret))
                .map(r -> r.targets().stream().map(sp -> sp.text(sql)).toList())
                .orElse(null);
        String name = abbreviate(sqlWithCaret);
        if (expected.length == 0) {
            check(name, actual != null && actual.isEmpty(), "expected empty targets, got " + actual);
        } else {
            check(name, List.of(expected).equals(actual), "expected " + List.of(expected) + ", got " + actual);
        }
    }

    private static void expectNone(String sqlWithCaret) {
        int caret = sqlWithCaret.indexOf('|');
        String sql = sqlWithCaret.substring(0, caret) + sqlWithCaret.substring(caret + 1);
        Optional<CrossReference> ref = InsertStatementParser.findAt(sql, caret).flatMap(s -> s.crossReferenceAt(caret));
        check(abbreviate(sqlWithCaret), ref.isEmpty(), "expected no reference, got " + ref);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add(name + " -> " + detail);
        }
    }

    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 70 ? one.substring(0, 67) + "..." : one;
    }
}
