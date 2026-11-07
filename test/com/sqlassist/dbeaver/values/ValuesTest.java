package com.sqlassist.dbeaver.values;

import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.InsertStatementParser;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free tests for Module 5 (run by build.sh). */
public final class ValuesTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        literals();
        json();
        xml();
        detection();

        System.out.println("Value editor tests: " + passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    static void literals() {
        roundTrip("'it''s'", "it's", "say 'hi'", "'say ''hi'''");
        roundTrip("N'x'", "x", "y'", "N'y'''");
        roundTrip("E'a\\nb\\'c'", "a\nb'c", "x\ty\\", "E'x\\ty\\\\'");
        roundTrip("$$a'b$$", "a'b", "has $$ inside", "$v1$has $$ inside$v1$");
        roundTrip("$j${\"a\":1}$j$", "{\"a\":1}", "{}", "$j${}$j$");
        check("hex not editable", StringLiteral.parse("X'FF'").isEmpty(), "");
        check("unterminated not editable", StringLiteral.parse("'abc").isEmpty(), "");
    }

    static void json() throws FormatException {
        String src = "{\"b\":[1,2.50,{\"c\":null}],\"a\":\"x\\\"y\",\"e\":{},\"f\":[]}";
        equal("json pretty", """
                {
                  "b": [
                    1,
                    2.50,
                    {
                      "c": null
                    }
                  ],
                  "a": "x\\"y",
                  "e": {},
                  "f": []
                }""", JsonFormatter.pretty(src));
        equal("json compact round trip", src, JsonFormatter.compact(JsonFormatter.pretty(src)));
        equal("json top-level scalar", "true", JsonFormatter.pretty(" true "));
        jsonError("{\"a\":1,}", "Property name in double quotes expected", "Line 1, column 8");
        jsonError("{\"a\" 1}", "':' expected", null);
        jsonError("[1 2]", "',' or ']' expected", null);
        jsonError("{\"a\":\"x}", "Unterminated string", null);
        jsonError("{\"a\":1}\nx", "Unexpected text after the JSON value", "Line 2, column 1");
        jsonError("{'a':1}", "Property name in double quotes expected", null);
        jsonError("[01]", "',' or ']' expected", null);
    }

    static void xml() {
        try {
            equal("xml pretty", "<a>\n  <b x=\"1\">t</b>\n  <c/>\n</a>", XmlFormatter.pretty("<a><b x=\"1\">t</b>   <c/></a>"));
        } catch (FormatException e) {
            failures.add("xml pretty -> " + e.getMessage());
        }
        try {
            XmlFormatter.pretty("<a><b></a>");
            failures.add("xml error -> no exception");
        } catch (FormatException e) {
            check("xml error has position", e.offset() > 0, "offset=" + e.offset());
        }
        try {
            XmlFormatter.pretty("<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>");
            failures.add("xml doctype -> accepted");
        } catch (FormatException e) {
            passed++; // DTDs refused (no XXE)
        }
    }

    static void detection() {
        String json = "{\"customer\": {\"id\": 42, \"tags\": [\"a\", \"b\"]}}";
        String text = "x".repeat(LongValues.TEXT_MIN_LENGTH);
        String sql = "INSERT INTO t (a, b, c, d, e, f) VALUES (1, '" + json + "', '<r><v>1</v><v>2</v><v>3</v></r>', '"
                + text + "', 'short', '{}'::jsonb)";
        InsertStatement s = InsertStatementParser.parseAll(sql).get(0);
        List<LongValues.Value> found = LongValues.find(s, sql);
        check("three long values", found.size() == 3, found.toString());
        check("kinds", found.size() == 3 && found.get(0).kind() == ValueKind.JSON
                && found.get(1).kind() == ValueKind.XML && found.get(2).kind() == ValueKind.TEXT, found.toString());
        int caret = sql.indexOf("short") + 2;
        check("short value reachable by command", LongValues.at(s, sql, caret)
                .map(v -> v.content().equals("short")).orElse(false), "");
        check("number is not a string value", LongValues.at(s, sql, sql.indexOf("(1,") + 1).isEmpty(), "");
        String multi = "INSERT INTO t (a, b) VALUES (1, 'line1\nline2')";
        check("multi-line text", LongValues.find(InsertStatementParser.parseAll(multi).get(0), multi).size() == 1, "");
    }

    // ------------------------------------------------------------------ helpers

    private static void roundTrip(String literal, String content, String newContent, String rendered) {
        StringLiteral l = StringLiteral.parse(literal).orElse(null);
        if (l == null) {
            failures.add(literal + " -> not parsed");
            return;
        }
        equal(literal + " content", content, l.content());
        equal(literal + " render", rendered, l.render(newContent));
        equal(literal + " reparse", newContent, StringLiteral.parse(l.render(newContent)).map(StringLiteral::content).orElse(null));
    }

    private static void jsonError(String json, String message, String position) {
        try {
            JsonFormatter.validate(json);
            failures.add(json + " -> accepted");
        } catch (FormatException e) {
            boolean ok = e.getMessage().equals(message) && (position == null || e.describe(json).startsWith(position));
            check(json, ok, e.describe(json));
        }
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
