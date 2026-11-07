package com.sqlassist.dbeaver.format;

import com.sqlassist.dbeaver.format.SqlFormatter.KeywordCase;
import com.sqlassist.dbeaver.format.SqlFormatter.Options;
import com.sqlassist.dbeaver.parser.SqlTokenizer;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free tests for the SQL formatter (run by build.sh). */
public final class SqlFormatterTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static final List<String> corpus = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        layout();
        comments();
        safety();
        keywordCase();
        unitsAndBlankLines();
        selectionAndCaret();
        wrapping();
        corpusIsStable();

        System.out.println("Formatter tests: " + passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    static void layout() {
        expect("select u.id, u.name, count(*) as cnt from users u join orders o on o.user_id = u.id "
                + "left join z on z.id = u.id where u.active = true and o.total > 10 or u.vip "
                + "group by u.id, u.name having count(*) > 1 order by cnt desc limit 10;", """
                SELECT u.id,
                       u.name,
                       count(*) AS cnt
                FROM users u
                JOIN orders o ON o.user_id = u.id
                LEFT JOIN z ON z.id = u.id
                WHERE u.active = TRUE
                  AND o.total > 10
                   OR u.vip
                GROUP BY u.id, u.name
                HAVING count(*) > 1
                ORDER BY cnt DESC
                LIMIT 10;""");
        expect("insert into users (id, name) values (1, 'a'), (2, 'b') on conflict (id) do update set name = excluded.name;", """
                INSERT INTO users (id, name)
                VALUES (1, 'a'),
                       (2, 'b')
                ON CONFLICT (id) DO UPDATE SET name = excluded.name;""");
        expect("update users set name = 'a', age = age + 1 where id in (select id from t where x between 1 and 5 and y = 2)", """
                UPDATE users
                SET name = 'a',
                    age = age + 1
                WHERE id IN (
                    SELECT id
                    FROM t
                    WHERE x BETWEEN 1 AND 5
                      AND y = 2
                )""");
        expect("delete from users where id = 1", "DELETE\nFROM users\nWHERE id = 1");
        expect("with a as (select 1 as x), b as (select x from a) select * from b union all select 2", """
                WITH a AS (
                    SELECT 1 AS x
                ),
                b AS (
                    SELECT x
                    FROM a
                )
                SELECT *
                FROM b
                UNION ALL
                SELECT 2""");
        expect("select case when a = 1 then 'one' else 'many' end as label from t", """
                SELECT CASE
                           WHEN a = 1 THEN 'one'
                           ELSE 'many'
                       END AS label
                FROM t""");
        expect("create table t (id serial primary key, name varchar(50) not null, unique (name, id))", """
                CREATE TABLE t (
                    id serial PRIMARY KEY,
                    name varchar(50) NOT NULL,
                    UNIQUE (name, id)
                )""");
        expect("select a from t where a is distinct from b", "SELECT a\nFROM t\nWHERE a IS DISTINCT FROM b");
        expect("insert into t (a, b) values (1, 2) on duplicate key update b = values(b)", """
                INSERT INTO t (a, b)
                VALUES (1, 2)
                ON DUPLICATE KEY UPDATE b = VALUES(b)""");
        expect("select * from (select a from t) x", "SELECT *\nFROM (\n    SELECT a\n    FROM t\n) x");
        expect("select top 10 a, b from t", "SELECT TOP 10 a,\n              b\nFROM t");
        expect("select a::timestamp with time zone from t", "SELECT a::timestamp WITH time zone\nFROM t");
        // Indent width option.
        expectWith(new Options(KeywordCase.UPPER, 2), "select * from (select 1)", "SELECT *\nFROM (\n  SELECT 1\n)");
    }

    static void comments() {
        expect("-- header\nselect a, -- first\n  b /* inline */, c\nfrom t -- tail\nwhere x = 1; -- after", """
                -- header
                SELECT a, -- first
                       b /* inline */,
                       c
                FROM t -- tail
                WHERE x = 1; -- after""");
        expect("select a\n-- explain the filter\nfrom t", "SELECT a\n-- explain the filter\nFROM t");
    }

    static void safety() throws Exception {
        // Adjacent tokens stay adjacent: operators the tokenizer splits must not be torn apart.
        expect("select $1::int, a->>'k', b>=2, c<>d, e||f from t", """
                SELECT $1::int,
                       a->>'k',
                       b>=2,
                       c<>d,
                       e||f
                FROM t""");
        // Strings, dollar quotes and quoted identifiers are never touched.
        expect("select 'a,  b\n  (c)', \"Mixed Case\", $$ x  y $$ from t",
                "SELECT 'a,  b\n  (c)',\n       \"Mixed Case\",\n       $$ x  y $$\nFROM t");
        // Unsupported statements are copied exactly.
        String fn = "CREATE FUNCTION f() RETURNS int AS $$ select   1 $$ LANGUAGE sql;\nalter table t   add column x int;";
        expect(fn, fn);
        // Unbalanced parentheses: copied as is.
        expect("select (a from t", "select (a from t");
        // The verifier rejects any change other than whitespace and keyword case.
        try {
            SqlFormatter.verify(SqlTokenizer.tokenizeWithComments("select a from t"), "select b from t");
            failures.add("verify accepted a changed identifier");
        } catch (SqlFormatException e) {
            passed++;
        }
        try {
            SqlFormatter.verify(SqlTokenizer.tokenizeWithComments("select name from t"), "select NAME from t");
            failures.add("verify accepted a re-cased identifier");
        } catch (SqlFormatException e) {
            passed++;
        }
    }

    static void keywordCase() {
        // Only reserved words change; identifiers that happen to be non-reserved keywords stay.
        expect("select first.id, t.order, public.user from t as first",
                "SELECT first.id,\n       t.order,\n       public.user\nFROM t AS first");
        expect("select offset, view, top from t", "SELECT offset,\n       view,\n       top\nFROM t");
        expectWith(new Options(KeywordCase.LOWER, 4), "SELECT A FROM T WHERE X IS NULL", "select A\nfrom T\nwhere X is null");
        expectWith(new Options(KeywordCase.PRESERVE, 4), "Select a From t", "Select a\nFrom t");
    }

    static void unitsAndBlankLines() {
        // Blank lines between statements survive (DBeaver may use them as separators); several become one.
        expect("select 1\n\n\n\nselect 2", "SELECT 1\n\nSELECT 2");
        // Statements on one line are split.
        expect("select 1; select 2;", "SELECT 1;\nSELECT 2;");
        // DBeaver client commands and GO are kept verbatim.
        expect("@set   x = 1\nselect   ${x}\nGO\n@include  file.sql", "@set   x = 1\nSELECT ${x}\nGO\n@include  file.sql");
    }

    static void selectionAndCaret() throws Exception {
        String in = "select a, b from t where x = 1";
        SqlFormatter.Result r = SqlFormatter.format(in, Options.DEFAULT, "    ");
        equal("line prefix", "    SELECT a,\n           b\n    FROM t\n    WHERE x = 1", r.text());
        int caret = in.indexOf("x = 1") + 1; // inside "x"... right after it
        int mapped = r.mapOffset(in, caret);
        equal("caret follows token", "x = 1", r.text().substring(mapped - 1, mapped - 1 + 5));
        SqlFormatter.Result r2 = SqlFormatter.format("select a\nfrom t", Options.DEFAULT);
        int atLineStart = "select a\n".length(); // caret before "from"
        equal("caret before next token", "FROM", r2.text().substring(r2.mapOffset("select a\nfrom t", atLineStart)).substring(0, 4));
    }

    static void wrapping() {
        Options narrow = new Options(KeywordCase.UPPER, 4, 50);
        // INSERT column list and VALUES rows wrap after a comma and align under the first item.
        expectWith(narrow, "insert into orders (order_id, customer_id, order_date, status, total) "
                + "values (1, 5021, '2026-11-07', 'PENDING_REVIEW', 10.5), (2, 5022, '2026-11-08', 'NEW', 3)", """
                INSERT INTO orders (order_id, customer_id,
                                    order_date, status, total)
                VALUES (1, 5021, '2026-11-07', 'PENDING_REVIEW',
                        10.5),
                       (2, 5022, '2026-11-08', 'NEW', 3)""");
        // IN lists and function arguments.
        expectWith(narrow, "select a from t where s in ('PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED')", """
                SELECT a
                FROM t
                WHERE s IN ('PENDING', 'PROCESSING', 'SHIPPED',
                            'DELIVERED')""");
        // GROUP BY / ORDER BY lists wrap only when needed, aligned after the keyword.
        expectWith(narrow, "select 1 from t group by customer_id, order_date, shipping_address, status order by a, b", """
                SELECT 1
                FROM t
                GROUP BY customer_id, order_date,
                         shipping_address, status
                ORDER BY a, b""");
        // A '(' far to the right: wrapped items fall back to an indent instead of a tiny column.
        expectWith(narrow, "select a, b, cccccccccccccccccccccccccccccc_function(alpha, beta, gamma, delta) from t", """
                SELECT a,
                       b,
                       cccccccccccccccccccccccccccccc_function(alpha,
                               beta, gamma, delta)
                FROM t""");
        // Line width 0 disables wrapping; lines under the margin are never wrapped.
        expectWith(new Options(KeywordCase.UPPER, 4, 0), "insert into orders (order_id, customer_id, order_date, status, total) values (1, 2, 3, 4, 5)",
                "INSERT INTO orders (order_id, customer_id, order_date, status, total)\nVALUES (1, 2, 3, 4, 5)");
        expect("insert into t (a, b) values (1, 2)", "INSERT INTO t (a, b)\nVALUES (1, 2)");
        // Default margin is 120, like DataGrip: no output line of a long statement is longer.
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            cols.append(i == 0 ? "" : ", ").append("column_number_").append(i);
            vals.append(i == 0 ? "" : ", ").append("'value ").append(i).append("'");
        }
        String big = "insert into wide_table (" + cols + ") values (" + vals + ")";
        corpus.add(big);
        try {
            String out = SqlFormatter.format(big, Options.DEFAULT).text();
            int longest = out.lines().mapToInt(String::length).max().orElse(0);
            check("lines fit in 120 columns", longest <= 120, "longest line " + longest);
        } catch (SqlFormatException e) {
            failures.add("wide insert -> " + e.getMessage());
        }
    }

    /** Formatting is idempotent and always passes its own safety check. */
    static void corpusIsStable() {
        for (String sql : corpus) {
            try {
                String once = SqlFormatter.format(sql, Options.DEFAULT).text();
                String twice = SqlFormatter.format(once, Options.DEFAULT).text();
                check("idempotent: " + abbreviate(sql), once.equals(twice), "\n    once:  " + once + "\n    twice: " + twice);
            } catch (SqlFormatException e) {
                failures.add("corpus " + abbreviate(sql) + " -> " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void expect(String in, String expected) {
        expectWith(Options.DEFAULT, in, expected);
    }

    private static void expectWith(Options options, String in, String expected) {
        corpus.add(in);
        try {
            equal(abbreviate(in), expected, SqlFormatter.format(in, options).text());
        } catch (SqlFormatException e) {
            failures.add(abbreviate(in) + " -> " + e.getMessage());
        }
    }

    private static void equal(String name, String expected, String actual) {
        check(name, expected.equals(actual), "\n--- expected\n" + expected + "\n--- actual\n" + actual);
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
        return one.length() > 60 ? one.substring(0, 57) + "..." : one;
    }
}
