package com.sqlassist.dbeaver.values;

import java.util.Optional;

/**
 * A SQL string literal split into its quoting and its actual content, so the content
 * can be edited as plain text and written back with the same quoting style.
 * <ul>
 *   <li>{@code 'it''s'} / {@code N'...'}: quote doubled</li>
 *   <li>{@code E'line\nnext'}: PostgreSQL backslash escapes</li>
 *   <li>{@code $$...$$} / {@code $tag$...$tag$}: no escaping at all</li>
 * </ul>
 * Hex/bit strings and unterminated literals are not editable.
 */
public record StringLiteral(Style style, String prefix, String tag, String content) {

    public enum Style { STANDARD, ESCAPED, DOLLAR }

    public static Optional<StringLiteral> parse(String literal) {
        String s = literal.strip();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        if (s.charAt(0) == '$') {
            int tagEnd = s.indexOf('$', 1);
            if (tagEnd < 0) {
                return Optional.empty();
            }
            String tag = s.substring(0, tagEnd + 1);
            if (s.length() < 2 * tag.length() || !s.endsWith(tag)) {
                return Optional.empty();
            }
            return Optional.of(new StringLiteral(Style.DOLLAR, "", tag, s.substring(tag.length(), s.length() - tag.length())));
        }
        int open = s.indexOf('\'');
        if (open < 0 || open > 1 || s.length() < open + 2 || !s.endsWith("'")) {
            return Optional.empty();
        }
        String prefix = s.substring(0, open);
        String body = s.substring(open + 1, s.length() - 1);
        return switch (prefix.toUpperCase()) {
            case "" , "N" -> Optional.of(new StringLiteral(Style.STANDARD, prefix, "", body.replace("''", "'")));
            case "E" -> Optional.of(new StringLiteral(Style.ESCAPED, prefix, "", unescapeBackslashes(body)));
            default -> Optional.empty(); // X'..', B'..'
        };
    }

    /** The literal for {@code newContent}, quoted the same way as this one. */
    public String render(String newContent) {
        return switch (style) {
            case STANDARD -> prefix + "'" + newContent.replace("'", "''") + "'";
            case ESCAPED -> prefix + "'" + escapeBackslashes(newContent) + "'";
            case DOLLAR -> {
                String t = tag;
                for (int i = 1; newContent.contains(t); i++) {
                    t = "$v" + i + "$"; // content would end the literal early: pick a fresh tag
                }
                yield t + newContent + t;
            }
        };
    }

    private static String unescapeBackslashes(String body) {
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(++i);
                sb.append(switch (n) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    default -> n;
                });
            } else if (c == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                sb.append('\'');
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeBackslashes(String content) {
        StringBuilder sb = new StringBuilder(content.length() + 8);
        for (char c : content.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
