package com.sqlassist.dbeaver.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, dialect-tolerant SQL lexer. It only needs to be good enough to find
 * parentheses and commas that are real separators, i.e. not inside strings,
 * quoted identifiers or comments. It never throws on malformed input:
 * an unterminated string/comment simply runs to the end of the text.
 */
public final class SqlTokenizer {

    private final CharSequence text;
    private final int limit;
    private int pos;

    private SqlTokenizer(CharSequence text, int from, int to) {
        this.text = text;
        this.pos = from;
        this.limit = to;
    }

    public static List<SqlToken> tokenize(CharSequence text) {
        return tokenize(text, 0, text.length());
    }

    public static List<SqlToken> tokenize(CharSequence text, int from, int to) {
        return new SqlTokenizer(text, from, to).run();
    }

    private List<SqlToken> run() {
        List<SqlToken> tokens = new ArrayList<>();
        while (pos < limit) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && peek(1) == '-') {
                skipLineComment();
            } else if (c == '/' && peek(1) == '*') {
                skipBlockComment();
            } else if (c == '\'') {
                tokens.add(quoted(SqlToken.Type.STRING, pos, '\''));
            } else if (c == '"') {
                tokens.add(quoted(SqlToken.Type.QUOTED_IDENT, pos, '"'));
            } else if (c == '`') {
                tokens.add(quoted(SqlToken.Type.QUOTED_IDENT, pos, '`'));
            } else if (c == '[') {
                tokens.add(bracketed(pos));
            } else if (c == '$' && dollarTagLength() > 0) {
                tokens.add(dollarQuoted());
            } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
                tokens.add(number());
            } else if (isWordStart(c)) {
                tokens.add(word());
            } else {
                int start = pos++;
                SqlToken.Type type = switch (c) {
                    case '(' -> SqlToken.Type.LPAREN;
                    case ')' -> SqlToken.Type.RPAREN;
                    case ',' -> SqlToken.Type.COMMA;
                    case ';' -> SqlToken.Type.SEMICOLON;
                    case '.' -> SqlToken.Type.DOT;
                    default -> SqlToken.Type.OTHER;
                };
                tokens.add(token(type, start));
            }
        }
        return tokens;
    }

    private char peek(int ahead) {
        int p = pos + ahead;
        return p < limit ? text.charAt(p) : '\0';
    }

    private SqlToken token(SqlToken.Type type, int start) {
        return new SqlToken(type, start, pos, text.subSequence(start, pos).toString());
    }

    private void skipLineComment() {
        while (pos < limit && text.charAt(pos) != '\n') {
            pos++;
        }
    }

    private void skipBlockComment() {
        pos += 2;
        while (pos < limit && !(text.charAt(pos) == '*' && peek(1) == '/')) {
            pos++;
        }
        pos = Math.min(limit, pos + 2);
    }

    private SqlToken quoted(SqlToken.Type type, int start, char quote) {
        return quoted(type, start, quote, false);
    }

    /**
     * Quote char doubled inside the literal is an escape ('it''s', "a""b").
     * With {@code backslashEscapes} (PostgreSQL E'..'), \' is an escape as well.
     */
    private SqlToken quoted(SqlToken.Type type, int start, char quote, boolean backslashEscapes) {
        pos++;
        while (pos < limit) {
            char c = text.charAt(pos++);
            if (backslashEscapes && c == '\\' && pos < limit) {
                pos++;
            } else if (c == quote) {
                if (pos < limit && text.charAt(pos) == quote) {
                    pos++;
                } else {
                    break;
                }
            }
        }
        return token(type, start);
    }

    private SqlToken bracketed(int start) {
        int depth = 0;
        while (pos < limit) {
            char c = text.charAt(pos++);
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                break;
            }
        }
        return token(SqlToken.Type.QUOTED_IDENT, start);
    }

    /** Length of a PostgreSQL dollar-quote tag at pos ("$$" -> 2, "$fn$" -> 4), or 0. */
    private int dollarTagLength() {
        int p = pos + 1;
        if (p < limit && Character.isDigit(text.charAt(p))) {
            return 0; // $1 is a positional parameter
        }
        while (p < limit && (Character.isLetterOrDigit(text.charAt(p)) || text.charAt(p) == '_')) {
            p++;
        }
        return p < limit && text.charAt(p) == '$' ? p - pos + 1 : 0;
    }

    private SqlToken dollarQuoted() {
        int start = pos;
        int tagLen = dollarTagLength();
        String tag = text.subSequence(pos, pos + tagLen).toString();
        pos += tagLen;
        while (pos < limit) {
            if (text.charAt(pos) == '$' && pos + tagLen <= limit
                    && text.subSequence(pos, pos + tagLen).toString().equals(tag)) {
                pos += tagLen;
                return token(SqlToken.Type.STRING, start);
            }
            pos++;
        }
        return token(SqlToken.Type.STRING, start);
    }

    private SqlToken number() {
        int start = pos;
        while (pos < limit && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '.'
                || ((text.charAt(pos) == '+' || text.charAt(pos) == '-')
                    && (text.charAt(pos - 1) == 'e' || text.charAt(pos - 1) == 'E')))) {
            pos++;
        }
        return token(SqlToken.Type.NUMBER, start);
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$' || c == '@' || c == '#' || c == ':' || c == '?';
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '@' || c == '#';
    }

    private SqlToken word() {
        int start = pos++;
        if (text.charAt(start) == '?' || (text.charAt(start) == ':' && !isWordPart(peek(0)))) {
            return token(SqlToken.Type.OTHER, start); // '?' placeholder, '::' cast etc.
        }
        while (pos < limit && isWordPart(text.charAt(pos))) {
            pos++;
        }
        // String prefixes: E'..', N'..', X'..', B'..', U&'..'
        if (pos < limit && text.charAt(pos) == '\'' && pos - start <= 2) {
            String prefix = text.subSequence(start, pos).toString().toUpperCase();
            if (prefix.equals("E") || prefix.equals("N") || prefix.equals("X") || prefix.equals("B")) {
                SqlToken s = quoted(SqlToken.Type.STRING, pos, '\'', prefix.equals("E"));
                return new SqlToken(SqlToken.Type.STRING, start, s.end(), text.subSequence(start, s.end()).toString());
            }
        }
        return token(SqlToken.Type.WORD, start);
    }
}
