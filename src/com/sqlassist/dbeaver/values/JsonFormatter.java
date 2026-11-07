package com.sqlassist.dbeaver.values;

/**
 * Dependency-free JSON validator, pretty-printer and minifier. Strings and numbers
 * are copied verbatim (no re-encoding, no precision loss); only whitespace changes.
 */
public final class JsonFormatter {

    private static final String INDENT = "  ";

    private final String in;
    private final boolean pretty;
    private final StringBuilder out = new StringBuilder();
    private int pos;

    private JsonFormatter(String in, boolean pretty) {
        this.in = in;
        this.pretty = pretty;
    }

    public static String pretty(String json) throws FormatException {
        return new JsonFormatter(json, true).run();
    }

    public static String compact(String json) throws FormatException {
        return new JsonFormatter(json, false).run();
    }

    public static void validate(String json) throws FormatException {
        compact(json);
    }

    private String run() throws FormatException {
        skipWs();
        value(0);
        skipWs();
        if (pos < in.length()) {
            throw error("Unexpected text after the JSON value");
        }
        return out.toString();
    }

    private void value(int depth) throws FormatException {
        if (pos >= in.length()) {
            throw error("Value expected");
        }
        char c = in.charAt(pos);
        switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> keyword("true");
            case 'f' -> keyword("false");
            case 'n' -> keyword("null");
            default -> {
                if (c == '-' || Character.isDigit(c)) {
                    number();
                } else {
                    throw error("Unexpected character '" + c + "'");
                }
            }
        }
    }

    private void object(int depth) throws FormatException {
        pos++;
        out.append('{');
        skipWs();
        if (peek() == '}') {
            pos++;
            out.append('}');
            return;
        }
        while (true) {
            newline(depth + 1);
            skipWs();
            if (peek() != '"') {
                throw error("Property name in double quotes expected");
            }
            string();
            skipWs();
            if (peek() != ':') {
                throw error("':' expected");
            }
            pos++;
            out.append(pretty ? ": " : ":");
            skipWs();
            value(depth + 1);
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                out.append(',');
                skipWs();
            } else if (c == '}') {
                pos++;
                newline(depth);
                out.append('}');
                return;
            } else {
                throw error("',' or '}' expected");
            }
        }
    }

    private void array(int depth) throws FormatException {
        pos++;
        out.append('[');
        skipWs();
        if (peek() == ']') {
            pos++;
            out.append(']');
            return;
        }
        while (true) {
            newline(depth + 1);
            skipWs();
            value(depth + 1);
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                out.append(',');
                skipWs();
            } else if (c == ']') {
                pos++;
                newline(depth);
                out.append(']');
                return;
            } else {
                throw error("',' or ']' expected");
            }
        }
    }

    private void string() throws FormatException {
        int start = pos++;
        while (pos < in.length()) {
            char c = in.charAt(pos);
            if (c == '"') {
                pos++;
                out.append(in, start, pos);
                return;
            }
            if (c < 0x20) {
                throw error("Control character in string (use \\n, \\t ...)");
            }
            if (c == '\\') {
                if (pos + 1 >= in.length()) {
                    break;
                }
                char e = in.charAt(pos + 1);
                if (e == 'u') {
                    if (pos + 6 > in.length() || !in.substring(pos + 2, pos + 6).matches("[0-9a-fA-F]{4}")) {
                        throw error("Invalid \\u escape");
                    }
                    pos += 6;
                    continue;
                }
                if ("\"\\/bfnrt".indexOf(e) < 0) {
                    throw error("Invalid escape \\" + e);
                }
                pos += 2;
                continue;
            }
            pos++;
        }
        pos = start;
        throw error("Unterminated string");
    }

    private void number() throws FormatException {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (!Character.isDigit(peek())) {
            throw error("Digit expected");
        }
        if (peek() == '0') {
            pos++;
        } else {
            digits();
        }
        if (peek() == '.') {
            pos++;
            if (!Character.isDigit(peek())) {
                throw error("Digit expected after '.'");
            }
            digits();
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!Character.isDigit(peek())) {
                throw error("Digit expected in exponent");
            }
            digits();
        }
        out.append(in, start, pos);
    }

    private void digits() {
        while (Character.isDigit(peek())) {
            pos++;
        }
    }

    private void keyword(String word) throws FormatException {
        if (!in.startsWith(word, pos)) {
            throw error("Unexpected token (expected " + word + ")");
        }
        pos += word.length();
        out.append(word);
    }

    private char peek() {
        return pos < in.length() ? in.charAt(pos) : '\0';
    }

    private void skipWs() {
        while (pos < in.length() && (in.charAt(pos) == ' ' || in.charAt(pos) == '\t'
                || in.charAt(pos) == '\n' || in.charAt(pos) == '\r')) {
            pos++;
        }
    }

    private void newline(int depth) {
        if (pretty) {
            out.append('\n').append(INDENT.repeat(depth));
        }
    }

    private FormatException error(String message) {
        return new FormatException(message, pos);
    }
}
