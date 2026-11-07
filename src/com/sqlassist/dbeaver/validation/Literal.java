package com.sqlassist.dbeaver.validation;

import com.sqlassist.dbeaver.parser.SqlToken;
import com.sqlassist.dbeaver.parser.SqlTokenizer;
import com.sqlassist.dbeaver.values.StringLiteral;

import java.util.List;

/**
 * Classifies a VALUES item. Only plain literals are checked; anything else
 * (functions, casts, sub-queries, parameters) is an {@link Kind#EXPRESSION} and is never flagged.
 *
 * @param value for STRING: the unquoted content; for NUMBER: the literal text
 */
public record Literal(Kind kind, String value) {

    public enum Kind { NULL, DEFAULT, STRING, NUMBER, BOOLEAN, EMPTY, EXPRESSION }

    public static Literal of(String text) {
        List<SqlToken> tokens = SqlTokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            return new Literal(Kind.EMPTY, "");
        }
        if (tokens.size() == 1) {
            SqlToken t = tokens.get(0);
            if (t.isWord("NULL")) {
                return new Literal(Kind.NULL, "NULL");
            }
            if (t.isWord("DEFAULT")) {
                return new Literal(Kind.DEFAULT, "DEFAULT");
            }
            if (t.isWord("TRUE") || t.isWord("FALSE")) {
                return new Literal(Kind.BOOLEAN, t.text().toUpperCase());
            }
            if (t.type() == SqlToken.Type.NUMBER && isNumeric(t.text())) {
                return new Literal(Kind.NUMBER, t.text());
            }
            if (t.type() == SqlToken.Type.STRING) {
                String content = stringContent(t.text());
                return content == null ? new Literal(Kind.EXPRESSION, t.text()) : new Literal(Kind.STRING, content);
            }
        }
        if (tokens.size() == 2 && tokens.get(1).type() == SqlToken.Type.NUMBER
                && (tokens.get(0).text().equals("-") || tokens.get(0).text().equals("+"))
                && tokens.get(0).end() == tokens.get(1).start() && isNumeric(tokens.get(1).text())) {
            return new Literal(Kind.NUMBER, tokens.get(0).text() + tokens.get(1).text());
        }
        return new Literal(Kind.EXPRESSION, text);
    }

    /** Content of 'x', N'x', E'x' or $$x$$; {@code null} for hex/bit strings and unterminated literals. */
    private static String stringContent(String token) {
        return StringLiteral.parse(token).map(StringLiteral::content).orElse(null);
    }

    static boolean isNumeric(String s) {
        return s.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
    }

    static boolean isInteger(String s) {
        return s.matches("[+-]?\\d+");
    }
}
