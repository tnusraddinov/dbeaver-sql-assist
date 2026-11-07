package com.sqlassist.dbeaver.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds INSERT statements in a token stream and splits their column list and
 * VALUES tuples / SELECT list into items, using parenthesis depth and commas.
 *
 * <p>Supported shapes (dialect-tolerant):
 * <ul>
 *   <li>{@code INSERT [modifiers] [INTO] t [AS a] (cols) VALUES (..), (..)} incl. MySQL {@code VALUES ROW(..)}</li>
 *   <li>{@code INSERT ... (cols) [OVERRIDING ... VALUE] [OUTPUT ...] SELECT e1, e2 FROM ...}</li>
 *   <li>{@code REPLACE INTO}, {@code UPSERT INTO}, {@code INSERT OR REPLACE INTO}, {@code INSERT IGNORE}</li>
 * </ul>
 * Nested expressions such as {@code now()}, {@code coalesce(a, b)}, {@code ARRAY[1,2]}, sub-selects,
 * strings containing commas/parentheses and comments are handled because only depth-1 commas split items.
 */
public final class InsertStatementParser {

    private static final Set<String> STATEMENT_KEYWORDS = Set.of("INSERT", "REPLACE", "UPSERT");
    private static final Set<String> TABLE_PREFIX_WORDS = Set.of(
            "INTO", "IGNORE", "LOW_PRIORITY", "DELAYED", "HIGH_PRIORITY", "OR", "REPLACE", "ROLLBACK",
            "ABORT", "FAIL", "OVERWRITE", "TABLE", "ONLY", "TOP");
    private static final Set<String> SELECT_LIST_END = Set.of(
            "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "UNION", "INTERSECT", "EXCEPT", "MINUS",
            "ON", "RETURNING", "WINDOW", "FETCH", "OFFSET", "FOR");

    /** Characters to look at around the caret when the document is too large to scan fully. */
    public static final int LARGE_DOCUMENT_THRESHOLD = 1_000_000;
    private static final int WINDOW_RADIUS = 200_000;

    private final List<SqlToken> tokens;
    private int pos;

    private InsertStatementParser(List<SqlToken> tokens) {
        this.tokens = tokens;
    }

    /** Parses every INSERT statement in {@code text}. */
    public static List<InsertStatement> parseAll(CharSequence text) {
        return parseAll(SqlTokenizer.tokenize(text), text);
    }

    public static List<InsertStatement> parseAll(List<SqlToken> tokens, CharSequence text) {
        InsertStatementParser parser = new InsertStatementParser(tokens);
        List<InsertStatement> result = new ArrayList<>();
        int depth = 0;
        while (parser.pos < tokens.size()) {
            SqlToken t = tokens.get(parser.pos);
            if (t.type() == SqlToken.Type.LPAREN) {
                depth++;
            } else if (t.type() == SqlToken.Type.RPAREN) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && t.type() == SqlToken.Type.WORD
                    && STATEMENT_KEYWORDS.contains(t.text().toUpperCase())
                    && !parser.isReplaceFunctionCall()) {
                int start = parser.pos;
                InsertStatement stmt = parser.parseInsert(text);
                if (stmt != null) {
                    result.add(stmt);
                    continue; // parser.pos already moved past the statement
                }
                parser.pos = start;
            }
            parser.pos++;
        }
        return result;
    }

    /**
     * Convenience entry point for the editor: finds the INSERT that contains {@code offset}.
     * Large documents are scanned only within a window around the caret.
     */
    public static Optional<InsertStatement> findAt(CharSequence text, int offset) {
        int from = 0;
        int to = text.length();
        if (to > LARGE_DOCUMENT_THRESHOLD) {
            from = lineStart(text, Math.max(0, offset - WINDOW_RADIUS));
            to = Math.min(text.length(), offset + WINDOW_RADIUS);
        }
        List<InsertStatement> statements = parseAll(SqlTokenizer.tokenize(text, from, to), text);
        return findAt(statements, offset);
    }

    public static Optional<InsertStatement> findAt(List<InsertStatement> statements, int offset) {
        // Statements are ordered by start offset -> binary search for the last one starting before the caret.
        int lo = 0;
        int hi = statements.size() - 1;
        int candidate = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (statements.get(mid).range().start() <= offset) {
                candidate = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (candidate >= 0 && statements.get(candidate).range().containsCaret(offset)) {
            return Optional.of(statements.get(candidate));
        }
        return Optional.empty();
    }

    private static int lineStart(CharSequence text, int offset) {
        int p = offset;
        while (p > 0 && text.charAt(p - 1) != '\n') {
            p--;
        }
        return p;
    }

    // ------------------------------------------------------------------ parsing

    /** {@code REPLACE(str, a, b)} is a function, not a statement. */
    private boolean isReplaceFunctionCall() {
        return tokens.get(pos).isWord("REPLACE") && pos + 1 < tokens.size()
                && tokens.get(pos + 1).type() == SqlToken.Type.LPAREN;
    }

    private SqlToken peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private boolean atEnd() {
        SqlToken t = peek();
        return t == null || t.type() == SqlToken.Type.SEMICOLON;
    }

    private InsertStatement parseInsert(CharSequence text) {
        SqlToken first = tokens.get(pos++);

        // 1. Skip modifiers (INTO, IGNORE, OR REPLACE, TOP (n) ...) and read the table name.
        while (!atEnd() && peek().type() == SqlToken.Type.WORD
                && TABLE_PREFIX_WORDS.contains(peek().text().toUpperCase())) {
            boolean top = peek().isWord("TOP");
            pos++;
            if (top) {
                skipBalancedOrSingle();
            }
        }
        if (atEnd() || !peek().isIdentifier() || isSourceKeyword(peek())) {
            return null;
        }
        int nameStart = peek().start();
        int nameEnd = peek().end();
        pos++;
        while (pos + 1 < tokens.size() && peek().type() == SqlToken.Type.DOT && tokens.get(pos + 1).isIdentifier()) {
            nameEnd = tokens.get(pos + 1).end();
            pos += 2;
        }
        Span tableSpan = new Span(nameStart, nameEnd);
        String tableName = tableSpan.text(text);

        // Optional alias / table hints before the column list: "AS t", "t", "WITH (TABLOCK)".
        if (!atEnd() && peek().isWord("AS")) {
            pos++;
            if (!atEnd() && peek().isIdentifier()) {
                pos++;
            }
        }
        if (!atEnd() && peek().isWord("WITH") && pos + 1 < tokens.size()
                && tokens.get(pos + 1).type() == SqlToken.Type.LPAREN) {
            pos++;
            skipBalancedOrSingle();
        }

        // 2. Column list.
        List<Span> columns = List.of();
        int lastEnd = tableSpan.end();
        if (!atEnd() && peek().type() == SqlToken.Type.LPAREN && !startsSubquery(pos + 1)) {
            ItemList list = parseParenthesizedItems();
            columns = list.items;
            lastEnd = list.closeEnd;
        }

        // 3. Find the row source: VALUES / VALUE / SELECT (skipping OVERRIDING ..., OUTPUT ... clauses).
        int depth = 0;
        while (!atEnd()) {
            SqlToken t = peek();
            if (t.type() == SqlToken.Type.LPAREN) {
                depth++;
            } else if (t.type() == SqlToken.Type.RPAREN) {
                if (--depth < 0) {
                    break; // INSERT nested inside something (e.g. a CTE) ended
                }
            } else if (depth == 0 && t.type() == SqlToken.Type.WORD) {
                String word = t.text().toUpperCase();
                if ((word.equals("VALUES") || word.equals("VALUE")) && !isOverridingValue()) {
                    pos++;
                    return parseValues(first, tableName, tableSpan, columns, lastEnd);
                }
                if (word.equals("SELECT")) {
                    pos++;
                    return parseSelectList(first, tableName, tableSpan, columns, lastEnd);
                }
                if (word.equals("DEFAULT") || word.equals("SET") || STATEMENT_KEYWORDS.contains(word)) {
                    break;
                }
            }
            lastEnd = t.end();
            pos++;
        }
        return new InsertStatement(new Span(first.start(), lastEnd), tableName, tableSpan, columns, List.of(),
                InsertStatement.SourceKind.NONE);
    }

    private boolean isOverridingValue() {
        return pos > 0 && (tokens.get(pos - 1).isWord("SYSTEM") || tokens.get(pos - 1).isWord("USER"));
    }

    private static boolean isSourceKeyword(SqlToken t) {
        return t.isWord("VALUES") || t.isWord("SELECT") || t.isWord("DEFAULT") || t.isWord("SET");
    }

    private boolean startsSubquery(int index) {
        return index < tokens.size() && (tokens.get(index).isWord("SELECT") || tokens.get(index).isWord("WITH"));
    }

    private InsertStatement parseValues(SqlToken first, String tableName, Span tableSpan, List<Span> columns,
                                        int lastEnd) {
        List<List<Span>> rows = new ArrayList<>();
        while (!atEnd()) {
            if (peek().isWord("ROW")) {
                pos++;
            }
            if (atEnd() || peek().type() != SqlToken.Type.LPAREN) {
                break;
            }
            ItemList row = parseParenthesizedItems();
            rows.add(row.items);
            lastEnd = row.closeEnd;
            if (!atEnd() && peek().type() == SqlToken.Type.COMMA) {
                pos++;
            } else {
                break;
            }
        }
        return new InsertStatement(new Span(first.start(), lastEnd), tableName, tableSpan, columns,
                List.copyOf(rows), InsertStatement.SourceKind.VALUES);
    }

    private InsertStatement parseSelectList(SqlToken first, String tableName, Span tableSpan, List<Span> columns,
                                            int lastEnd) {
        while (!atEnd() && (peek().isWord("DISTINCT") || peek().isWord("ALL") || peek().isWord("TOP"))) {
            boolean top = peek().isWord("TOP");
            pos++;
            if (top) {
                skipBalancedOrSingle();
            }
        }
        List<Span> items = new ArrayList<>();
        int depth = 0;
        int itemStart = -1;
        int itemEnd = -1;
        int separatorEnd = pos < tokens.size() ? tokens.get(pos).start() : lastEnd;
        while (!atEnd()) {
            SqlToken t = peek();
            if (depth == 0 && t.type() == SqlToken.Type.WORD && SELECT_LIST_END.contains(t.text().toUpperCase())) {
                break;
            }
            if (t.type() == SqlToken.Type.RPAREN && depth == 0) {
                break;
            }
            if (depth == 0 && t.type() == SqlToken.Type.COMMA) {
                items.add(itemStart < 0 ? new Span(separatorEnd, separatorEnd) : new Span(itemStart, itemEnd));
                itemStart = -1;
                separatorEnd = t.end();
                pos++;
                continue;
            }
            if (t.type() == SqlToken.Type.LPAREN) {
                depth++;
            } else if (t.type() == SqlToken.Type.RPAREN) {
                depth--;
            }
            if (itemStart < 0) {
                itemStart = t.start();
            }
            itemEnd = t.end();
            pos++;
        }
        if (itemStart >= 0) {
            items.add(new Span(itemStart, itemEnd));
            lastEnd = itemEnd;
        }
        return new InsertStatement(new Span(first.start(), lastEnd), tableName, tableSpan, columns,
                items.isEmpty() ? List.of() : List.of(List.copyOf(items)), InsertStatement.SourceKind.SELECT);
    }

    private record ItemList(List<Span> items, int closeEnd) { }

    /**
     * pos is on '('. Splits the content by commas at depth 1. Empty items (e.g. {@code (1,,2)})
     * are kept as zero-length spans so that indices stay aligned with the column list.
     */
    private ItemList parseParenthesizedItems() {
        SqlToken open = tokens.get(pos++);
        List<Span> items = new ArrayList<>();
        int depth = 1;
        int itemStart = -1;
        int itemEnd = -1;
        int separatorEnd = open.end();
        int closeEnd = open.end();
        while (pos < tokens.size()) {
            SqlToken t = tokens.get(pos);
            if (t.type() == SqlToken.Type.SEMICOLON && depth == 1) {
                break; // unterminated tuple; keep what we have
            }
            if (t.type() == SqlToken.Type.RPAREN && depth == 1) {
                closeEnd = t.end();
                pos++;
                if (itemStart >= 0) {
                    items.add(new Span(itemStart, itemEnd));
                } else if (!items.isEmpty()) {
                    items.add(new Span(separatorEnd, separatorEnd)); // trailing comma
                }
                return new ItemList(List.copyOf(items), closeEnd);
            }
            if (t.type() == SqlToken.Type.COMMA && depth == 1) {
                items.add(itemStart < 0 ? new Span(separatorEnd, separatorEnd) : new Span(itemStart, itemEnd));
                itemStart = -1;
                separatorEnd = t.end();
                pos++;
                continue;
            }
            if (t.type() == SqlToken.Type.LPAREN) {
                depth++;
            } else if (t.type() == SqlToken.Type.RPAREN) {
                depth--;
            }
            if (itemStart < 0) {
                itemStart = t.start();
            }
            itemEnd = t.end();
            closeEnd = t.end();
            pos++;
        }
        // Unterminated "(...": the user is still typing. Keep partial items for live feedback.
        if (itemStart >= 0) {
            items.add(new Span(itemStart, itemEnd));
        }
        return new ItemList(List.copyOf(items), closeEnd);
    }

    /** Skips "(...)" or a single token (used for TOP n / TOP (n) and WITH (hints)). */
    private void skipBalancedOrSingle() {
        if (atEnd()) {
            return;
        }
        if (peek().type() != SqlToken.Type.LPAREN) {
            pos++;
            return;
        }
        int depth = 0;
        while (pos < tokens.size()) {
            SqlToken t = tokens.get(pos++);
            if (t.type() == SqlToken.Type.LPAREN) {
                depth++;
            } else if (t.type() == SqlToken.Type.RPAREN && --depth == 0) {
                return;
            }
        }
    }
}
