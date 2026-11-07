package com.sqlassist.dbeaver.format;

import com.sqlassist.dbeaver.parser.SqlToken;
import com.sqlassist.dbeaver.parser.SqlToken.Type;
import com.sqlassist.dbeaver.parser.SqlTokenizer;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DataGrip-style SQL formatter. Pure Java, no Eclipse/DBeaver dependencies.
 *
 * <pre>
 * SELECT u.id,
 *        u.name
 * FROM users u
 * JOIN orders o ON o.user_id = u.id
 * WHERE u.active = TRUE
 *   AND o.total > 10
 * ORDER BY u.name;
 *
 * INSERT INTO users (id, name)
 * VALUES (1, 'a'),
 *        (2, 'b');
 * </pre>
 *
 * <p>Safety rules:
 * <ul>
 *   <li>Only whitespace and the letter case of reserved keywords change. The result is
 *       re-tokenized and compared with the input; any other difference aborts with
 *       {@link SqlFormatException}.</li>
 *   <li>Tokens that touch in the input keep touching ({@code ::}, {@code >=}, {@code ->>} stay intact).</li>
 *   <li>Blank lines are kept (DBeaver can use them as statement separators).</li>
 *   <li>Only SELECT / WITH / INSERT / UPDATE / DELETE / VALUES / CREATE TABLE / CREATE VIEW statements
 *       are re-laid out; anything else, and statements with unbalanced parentheses, are copied verbatim.
 *       DBeaver client commands ({@code @set ...}), {@code DELIMITER} and {@code GO} lines are kept as is.</li>
 * </ul>
 */
public final class SqlFormatter {

    public enum KeywordCase { UPPER, LOWER, PRESERVE }

    /**
     * @param lineWidth right margin: comma-separated lists that would cross it are wrapped
     *                  (0 = never wrap). DataGrip's default is 120.
     */
    public record Options(KeywordCase keywordCase, int indentWidth, int lineWidth) {
        public static final int DEFAULT_LINE_WIDTH = 120;
        public static final Options DEFAULT = new Options(KeywordCase.UPPER, 4, DEFAULT_LINE_WIDTH);

        public Options(KeywordCase keywordCase, int indentWidth) {
            this(keywordCase, indentWidth, DEFAULT_LINE_WIDTH);
        }
    }

    /** Formatted text, plus where every input token ended up (for keeping the caret in place). */
    public static final class Result {
        private final String text;
        private final List<SqlToken> tokens;
        private final int[] outStart;

        Result(String text, List<SqlToken> tokens, int[] outStart) {
            this.text = text;
            this.tokens = tokens;
            this.outStart = outStart;
        }

        public String text() {
            return text;
        }

        /** Maps an offset in the input to the corresponding offset in {@link #text()}. */
        public int mapOffset(String input, int offset) {
            if (tokens.isEmpty()) {
                return Math.min(offset, text.length());
            }
            int lo = 0;
            int hi = tokens.size() - 1;
            int idx = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (tokens.get(mid).start() <= offset) {
                    idx = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (idx < 0) {
                return outStart[0];
            }
            SqlToken t = tokens.get(idx);
            if (offset <= t.end()) {
                return outStart[idx] + (offset - t.start());
            }
            // In the whitespace after token idx: stick to the next token if the caret is on its line.
            if (idx + 1 < tokens.size()) {
                SqlToken next = tokens.get(idx + 1);
                if (input.substring(offset, next.start()).indexOf('\n') < 0
                        && input.substring(t.end(), offset).indexOf('\n') >= 0) {
                    return outStart[idx + 1];
                }
            }
            return Math.min(text.length(), outStart[idx] + t.text().length());
        }
    }

    private SqlFormatter() {
    }

    public static Result format(String sql, Options options) throws SqlFormatException {
        return format(sql, options, "");
    }

    /**
     * @param linePrefix prepended to every non-empty output line (indentation of a formatted selection)
     */
    public static Result format(String sql, Options options, String linePrefix) throws SqlFormatException {
        List<SqlToken> tokens = SqlTokenizer.tokenizeWithComments(sql);
        Engine engine = new Engine(sql, tokens, options);
        String out = engine.run();
        int[] outStart = engine.outStart;
        if (!linePrefix.isEmpty()) {
            PrefixResult p = prefixLines(out, linePrefix, outStart);
            out = p.text;
            outStart = p.offsets;
        }
        verify(tokens, out);
        return new Result(out, tokens, outStart);
    }

    // ------------------------------------------------------------------ safety check

    static void verify(List<SqlToken> input, String output) throws SqlFormatException {
        List<SqlToken> result = SqlTokenizer.tokenizeWithComments(output);
        if (result.size() != input.size()) {
            throw new SqlFormatException("token count changed (" + input.size() + " -> " + result.size() + ")");
        }
        for (int i = 0; i < input.size(); i++) {
            SqlToken a = input.get(i);
            SqlToken b = result.get(i);
            boolean same = a.type() == b.type() && (a.text().equals(b.text())
                    || (a.type() == Type.WORD && a.text().equalsIgnoreCase(b.text()) && Keywords.mayChangeCase(a.text())));
            if (!same) {
                throw new SqlFormatException("token " + (i + 1) + " changed: '" + a.text() + "' -> '" + b.text() + "'");
            }
        }
    }

    private record PrefixResult(String text, int[] offsets) { }

    private static PrefixResult prefixLines(String text, String prefix, int[] offsets) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int[] shiftAt = new int[text.length() + 1]; // shift for an offset in the original output
        int shift = 0;
        boolean lineStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (lineStart && c != '\n') {
                sb.append(prefix);
                shift += prefix.length();
            }
            shiftAt[i] = shift;
            sb.append(c);
            lineStart = c == '\n';
        }
        shiftAt[text.length()] = shift;
        int[] mapped = new int[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            mapped[i] = offsets[i] + shiftAt[Math.min(offsets[i], text.length())];
        }
        return new PrefixResult(sb.toString(), mapped);
    }

    // ------------------------------------------------------------------ engine

    private enum Clause { NONE, WITH, SELECT, FROM, JOIN, ON, WHERE, HAVING, GROUP, ORDER, SET, VALUES, OTHER }

    private enum Kind { FORMAT, CREATE_TABLE, VERBATIM }

    private static final class Engine {
        private final String src;
        private final List<SqlToken> t;
        private final int n;
        private final Options o;
        private final int[] match;
        final int[] outStart;
        private final Set<Integer> recognised = new HashSet<>();
        private final StringBuilder sb = new StringBuilder();
        private int lineStart;
        private boolean fresh = true;
        private int pending = -1;
        private int last = -1;

        Engine(String src, List<SqlToken> tokens, Options options) {
            this.src = src;
            this.t = tokens;
            this.n = tokens.size();
            this.o = options;
            this.outStart = new int[n];
            this.match = new int[n];
            Arrays.fill(match, -1);
            Deque<Integer> open = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                if (is(i, Type.LPAREN)) {
                    open.push(i);
                } else if (is(i, Type.RPAREN) && !open.isEmpty()) {
                    int j = open.pop();
                    match[j] = i;
                    match[i] = j;
                }
            }
        }

        String run() {
            int i = 0;
            while (i < n) {
                boolean raw = isRawLine(i);
                int end = raw ? rawLineEnd(i) : statementEnd(i);
                Kind kind = raw ? Kind.VERBATIM : kindOf(i, end);
                if (i > 0) {
                    int nl = newlinesBefore(i);
                    if (nl >= 2) {
                        blankLine();
                    } else if (nl == 1 || kind != Kind.VERBATIM || lastUnitFormatted) {
                        newline(0);
                    } else {
                        write(src.substring(t.get(i - 1).end(), t.get(i).start()));
                    }
                }
                if (kind == Kind.VERBATIM) {
                    copy(i, end);
                    lastUnitFormatted = false;
                } else {
                    new Statement(i, end, 0, kind).format();
                    lastUnitFormatted = true;
                }
                i = end;
            }
            trimTrailing();
            return sb.toString();
        }

        private boolean lastUnitFormatted;

        // ---------------------------------------------------------- units

        /** DBeaver script commands (not T-SQL @variables, which are never alone at a line start like these). */
        private static final Set<String> DBEAVER_COMMANDS = Set.of("@set", "@unset", "@include", "@echo", "@export");

        /** DBeaver client commands, DELIMITER and GO are line-oriented: never touch them. */
        private boolean isRawLine(int i) {
            SqlToken tk = t.get(i);
            if (i > 0 && newlinesBefore(i) == 0) {
                return false;
            }
            if (tk.type() == Type.WORD && DBEAVER_COMMANDS.contains(tk.text().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (tk.isWord("DELIMITER")) {
                return true;
            }
            if (tk.type() == Type.OTHER && tk.text().equals("\\")) {
                return true; // psql meta-command
            }
            return tk.isWord("GO") && (i + 1 >= n || newlinesBefore(i + 1) > 0);
        }

        private int rawLineEnd(int i) {
            int lineEnd = src.indexOf('\n', t.get(i).start());
            if (lineEnd < 0) {
                lineEnd = src.length();
            }
            int j = i + 1;
            while (j < n && t.get(j).start() < lineEnd) {
                j++;
            }
            return j;
        }

        /** Up to and including a depth-0 ';' (plus same-line trailing comments), or before a blank line. */
        private int statementEnd(int i) {
            int depth = 0;
            int j = i;
            while (j < n) {
                if (j > i && newlinesBefore(j) >= 2) {
                    return j;
                }
                if (j > i && depth == 0 && isRawLine(j)) {
                    return j;
                }
                SqlToken tk = t.get(j);
                if (tk.type() == Type.LPAREN) {
                    depth++;
                } else if (tk.type() == Type.RPAREN) {
                    depth = Math.max(0, depth - 1);
                }
                j++;
                if (tk.type() == Type.SEMICOLON && depth == 0) {
                    while (j < n && t.get(j).type() == Type.COMMENT && newlinesBefore(j) == 0) {
                        j++;
                    }
                    return j;
                }
            }
            return j;
        }

        private Kind kindOf(int from, int to) {
            for (int k = from; k < to; k++) {
                if ((is(k, Type.LPAREN) || is(k, Type.RPAREN)) && (match[k] < from || match[k] >= to)) {
                    return Kind.VERBATIM; // unbalanced parentheses: do not guess
                }
            }
            int f = skipComments(from, to);
            if (f >= to) {
                return Kind.VERBATIM;
            }
            if (is(f, Type.LPAREN)) {
                int g = skipComments(f + 1, to);
                return g < to && (word(g, "SELECT") || word(g, "WITH")) ? Kind.FORMAT : Kind.VERBATIM;
            }
            String w = upper(f);
            switch (w) {
                case "SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "VALUES", "UPSERT":
                    return Kind.FORMAT;
                case "REPLACE":
                    return f + 1 < to && is(f + 1, Type.LPAREN) ? Kind.VERBATIM : Kind.FORMAT;
                case "CREATE":
                    int k = f + 1;
                    if (word(k, "OR") && word(k + 1, "REPLACE")) {
                        k += 2;
                    }
                    while (k < to && (word(k, "TEMP") || word(k, "TEMPORARY") || word(k, "UNLOGGED")
                            || word(k, "GLOBAL") || word(k, "LOCAL"))) {
                        k++;
                    }
                    if (word(k, "VIEW")) {
                        recognised.add(k);
                        return Kind.FORMAT;
                    }
                    return word(k, "TABLE") ? Kind.CREATE_TABLE : Kind.VERBATIM;
                default:
                    return Kind.VERBATIM;
            }
        }

        // ---------------------------------------------------------- statement layout

        private final class Statement {
            private final int from;
            private final int to;
            private final int base;
            private final Kind kind;
            private Clause clause = Clause.NONE;
            private int alignCol = -1;
            private boolean alignAtNext;
            private int wrapCol = -1;
            private boolean wrapAtNext;
            private boolean between;
            private boolean started;
            private boolean updateStatement;
            private boolean createTableParenDone;
            private final Deque<Integer> cases = new ArrayDeque<>();

            Statement(int from, int to, int base, Kind kind) {
                this.from = from;
                this.to = to;
                this.base = base;
                this.kind = kind;
            }

            void format() {
                int f = skipComments(from, to);
                updateStatement = f < to && word(f, "UPDATE");
                int k = from;
                while (k < to) {
                    k = step(k);
                }
            }

            private int unit() {
                return o.indentWidth();
            }

            private int contIndent() {
                if (!started) {
                    return base;
                }
                return alignCol >= 0 ? alignCol : base + unit();
            }

            /** Handles token k (and possibly following ones); returns the next index. */
            private int step(int k) {
                SqlToken tk = t.get(k);
                if (tk.type() == Type.COMMENT) {
                    comment(k, contIndent());
                    return k + 1;
                }
                if (tk.type() == Type.LPAREN && match[k] > k && match[k] < to) {
                    started = true;
                    boolean createList = kind == Kind.CREATE_TABLE && !createTableParenDone && clause == Clause.NONE;
                    if (createList) {
                        createTableParenDone = true;
                    }
                    paren(k, createList);
                    return match[k] + 1;
                }
                if (tk.type() == Type.COMMA) {
                    emit(k);
                    if (cases.isEmpty()) {
                        if ((clause == Clause.SELECT || clause == Clause.SET || clause == Clause.VALUES) && alignCol >= 0) {
                            pending = alignCol;
                        } else if (clause == Clause.WITH) {
                            pending = base;
                        } else if (wrapCol >= 0) {
                            wrapIfNeeded(k + 1, clauseItemEnd(k + 1), wrapCol);
                        }
                    }
                    return k + 1;
                }
                if (tk.type() == Type.WORD && !nearDot(k)) {
                    int len = clauseLength(k);
                    if (len > 0) {
                        return clauseKeyword(k, len);
                    }
                    String w = upper(k);
                    if ((w.equals("AND") || w.equals("OR")) && cases.isEmpty()) {
                        if (between && w.equals("AND")) {
                            between = false;
                        } else if (clause == Clause.WHERE || clause == Clause.HAVING) {
                            int kw = clause == Clause.WHERE ? 5 : 6;
                            pending = base + Math.max(0, kw - w.length());
                        } else if (clause == Clause.ON) {
                            pending = base + unit();
                        }
                    } else if (w.equals("DO") && (word(k + 1, "UPDATE") || word(k + 1, "NOTHING"))) {
                        recognised.add(k); // ON CONFLICT ... DO UPDATE / DO NOTHING
                        recognised.add(k + 1);
                    } else if (w.equals("BETWEEN")) {
                        between = true;
                    } else if (w.equals("ON") && clause == Clause.JOIN) {
                        clause = Clause.ON;
                    } else if (w.equals("CASE")) {
                        emit(k);
                        cases.push(col() - tk.text().length());
                        return k + 1;
                    } else if ((w.equals("WHEN") || w.equals("ELSE")) && !cases.isEmpty()) {
                        pending = cases.peek() + unit();
                    } else if (w.equals("END") && !cases.isEmpty()) {
                        pending = cases.pop();
                    }
                }
                emit(k);
                return k + 1;
            }

            private void emit(int k) {
                started = true;
                Engine.this.emit(k);
                if (alignAtNext) {
                    alignCol = col() - t.get(k).text().length();
                    alignAtNext = false;
                }
                if (wrapAtNext) {
                    wrapCol = wrapColumn(col() - t.get(k).text().length());
                    wrapAtNext = false;
                }
            }

            /** End (exclusive) of a clause-level list item starting at k. */
            private int clauseItemEnd(int k) {
                int j = k;
                while (j < to) {
                    SqlToken tk = t.get(j);
                    if (tk.type() == Type.COMMA || tk.type() == Type.SEMICOLON
                            || (tk.type() == Type.WORD && !nearDot(j) && clauseLength(j) > 0)) {
                        break;
                    }
                    j = tk.type() == Type.LPAREN && match[j] > j && match[j] < to ? match[j] + 1 : j + 1;
                }
                return j;
            }

            /** Number of tokens forming a clause keyword at k (GROUP BY = 2), or 0. */
            private int clauseLength(int k) {
                String w = upper(k);
                switch (w) {
                    case "SELECT", "FROM", "WHERE", "HAVING", "LIMIT", "FETCH", "WINDOW", "RETURNING", "QUALIFY":
                        if (w.equals("FROM") && isDistinctFrom(k)) {
                            return 0;
                        }
                        if (w.equals("FETCH") || w.equals("LIMIT")) {
                            return clause == Clause.NONE && !started ? 0 : 1;
                        }
                        return 1;
                    case "WITH":
                        return started ? 0 : 1; // WITH TIME ZONE, WITH (hints) etc. are not clauses
                    case "OFFSET":
                        return k + 1 < to && (is(k + 1, Type.NUMBER) || is(k + 1, Type.WORD)) ? 1 : 0;
                    case "GROUP", "ORDER":
                        return word(k + 1, "BY") ? 2 : 0;
                    case "UNION", "INTERSECT", "EXCEPT", "MINUS":
                        return word(k + 1, "ALL") || word(k + 1, "DISTINCT") ? 2 : 1;
                    case "JOIN":
                        return 1;
                    case "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL": {
                        int j = k + 1;
                        if (w.equals("NATURAL") && (word(j, "LEFT") || word(j, "RIGHT") || word(j, "FULL") || word(j, "INNER"))) {
                            j++;
                        }
                        if (word(j, "OUTER")) {
                            j++;
                        }
                        if (word(j, "JOIN")) {
                            return j - k + 1;
                        }
                        return w.equals("CROSS") && word(k + 1, "APPLY") ? 2 : 0;
                    }
                    case "OUTER":
                        return word(k + 1, "APPLY") ? 2 : 0;
                    case "ON":
                        if (word(k + 1, "CONFLICT")) {
                            return 2;
                        }
                        return word(k + 1, "DUPLICATE") && word(k + 2, "KEY") && word(k + 3, "UPDATE") ? 4 : 0;
                    case "VALUES": {
                        int p = prevSignificant(k);
                        if (p >= from && (is(p, Type.OTHER) || is(p, Type.COMMA) || is(p, Type.LPAREN))) {
                            return 0; // VALUES(col) function in ON DUPLICATE KEY UPDATE
                        }
                        return 1;
                    }
                    case "SET":
                        return updateStatement && clause != Clause.SET && !(k > from && word(k - 1, "UPDATE")) ? 1 : 0;
                    case "INSERT", "UPDATE", "DELETE":
                        return clause == Clause.WITH ? 1 : 0;
                    default:
                        return 0;
                }
            }

            private boolean isDistinctFrom(int k) {
                int p = prevSignificant(k);
                return p >= from && word(p, "DISTINCT");
            }

            private int clauseKeyword(int k, int len) {
                String w = upper(k);
                boolean first = !started;
                started = true;
                cases.clear();
                between = false;
                alignCol = -1;
                alignAtNext = false;
                wrapCol = -1;
                wrapAtNext = false;
                if (!first) {
                    pending = -1;
                    newline(base);
                }
                for (int j = k; j < k + len; j++) {
                    recognised.add(j);
                    Engine.this.emit(j);
                }
                int next = k + len;
                switch (w) {
                    case "SELECT" -> {
                        clause = Clause.SELECT;
                        // SELECT DISTINCT / ALL / DISTINCT ON (...) / TOP n: keep on the SELECT line
                        while (next < to && t.get(next).type() == Type.WORD
                                && (word(next, "DISTINCT") || word(next, "ALL") || word(next, "TOP") || word(next, "ON"))) {
                            boolean top = word(next, "TOP");
                            boolean on = word(next, "ON");
                            recognised.add(next);
                            Engine.this.emit(next);
                            next++;
                            if ((top || on) && next < to) {
                                if (is(next, Type.LPAREN) && match[next] > next && match[next] < to) {
                                    paren(next, false);
                                    next = match[next] + 1;
                                } else if (top) {
                                    Engine.this.emit(next);
                                    next++;
                                }
                            }
                        }
                        alignAtNext = true;
                    }
                    case "WITH" -> clause = Clause.WITH;
                    case "FROM" -> {
                        clause = Clause.FROM;
                        wrapAtNext = true;
                    }
                    case "WHERE" -> clause = Clause.WHERE;
                    case "HAVING" -> clause = Clause.HAVING;
                    case "GROUP" -> {
                        clause = Clause.GROUP;
                        wrapAtNext = true;
                    }
                    case "ORDER" -> {
                        clause = Clause.ORDER;
                        wrapAtNext = true;
                    }
                    case "SET" -> {
                        clause = Clause.SET;
                        alignAtNext = true;
                    }
                    case "VALUES" -> {
                        clause = Clause.VALUES;
                        alignAtNext = true;
                    }
                    case "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER" -> clause = Clause.JOIN;
                    case "INSERT", "UPDATE", "DELETE" -> {
                        clause = Clause.OTHER;
                        updateStatement |= w.equals("UPDATE");
                    }
                    default -> {
                        clause = Clause.OTHER;
                        wrapAtNext = true; // RETURNING a, b, ...
                    }
                }
                return next;
            }

            /** k is '(' with a matching ')' inside this statement. */
            private void paren(int k, boolean createList) {
                int close = match[k];
                int inner = skipComments(k + 1, close);
                boolean subquery = inner < close && (word(inner, "SELECT") || word(inner, "WITH")
                        || (word(inner, "VALUES") && clause != Clause.VALUES));
                emit(k); // may be the first item of an aligned list, e.g. the first VALUES row
                if (inner >= close) {
                    copyInline(k + 1, close);
                    Engine.this.emit(close);
                    return;
                }
                if (subquery || createList) {
                    int indent = lineIndent();
                    pending = -1;
                    newline(indent + unit());
                    if (subquery) {
                        new Statement(k + 1, close, indent + unit(), Kind.FORMAT).format();
                    } else {
                        list(k + 1, close, indent + unit());
                    }
                    pending = -1;
                    newline(indent);
                    Engine.this.emit(close);
                    return;
                }
                inline(k + 1, close);
                Engine.this.emit(close);
            }

            /** CREATE TABLE body: one column / constraint per line. */
            private void list(int from, int to, int indent) {
                int k = from;
                while (k < to) {
                    SqlToken tk = t.get(k);
                    if (tk.type() == Type.COMMENT) {
                        comment(k, indent);
                        k++;
                    } else if (tk.type() == Type.LPAREN && match[k] > k && match[k] < to) {
                        Engine.this.emit(k);
                        inline(k + 1, match[k]);
                        Engine.this.emit(match[k]);
                        k = match[k] + 1;
                    } else {
                        Engine.this.emit(k);
                        if (tk.type() == Type.COMMA) {
                            pending = indent;
                        }
                        k++;
                    }
                }
            }

            /** Function arguments, IN lists, column lists: one line, nested subqueries still get blocks. */
            private void inline(int from, int to) {
                int k = from;
                int listCol = -1;
                boolean itemStart = true;
                while (k < to) {
                    SqlToken tk = t.get(k);
                    if (tk.type() == Type.COMMENT) {
                        comment(k, lineIndent() + unit());
                        k++;
                        continue;
                    }
                    if (itemStart) {
                        if (listCol < 0) {
                            listCol = wrapColumn(col()); // items line up after the '('
                        } else {
                            wrapIfNeeded(k, inlineItemEnd(k, to), listCol);
                        }
                        itemStart = false;
                    }
                    if (tk.type() == Type.LPAREN && match[k] > k && match[k] < to) {
                        paren(k, false);
                        k = match[k] + 1;
                    } else {
                        Engine.this.emit(k);
                        itemStart = tk.type() == Type.COMMA;
                        k++;
                    }
                }
            }

            private int inlineItemEnd(int k, int to) {
                int j = k;
                while (j < to && t.get(j).type() != Type.COMMA) {
                    j = is(j, Type.LPAREN) && match[j] > j && match[j] < to ? match[j] + 1 : j + 1;
                }
                return j;
            }

            /** Align wrapped items at col, unless that is so far right that little room is left. */
            private int wrapColumn(int col) {
                int width = o.lineWidth();
                return width > 0 && col > width * 2 / 3 ? lineIndent() + 2 * unit() : col;
            }

            /** Breaks the line before item [k, end) if it would cross the right margin. */
            private void wrapIfNeeded(int k, int end, int col) {
                if (o.lineWidth() <= 0 || pending >= 0 || fresh || col() <= col) {
                    return;
                }
                int width = flatWidth(k, end);
                if (width < 0) {
                    return;
                }
                int start = col() + (needSpace(last, k) ? 1 : 0);
                if (start + width + 1 > o.lineWidth()) { // +1: the ',' or ')' that follows
                    pending = col;
                }
            }

            private void copyInline(int from, int to) {
                for (int k = from; k < to; k++) {
                    if (t.get(k).type() == Type.COMMENT) {
                        comment(k, lineIndent() + unit());
                    } else {
                        Engine.this.emit(k);
                    }
                }
            }

            private void comment(int k, int indent) {
                SqlToken tk = t.get(k);
                boolean sameLine = (k > 0 && newlinesBefore(k) == 0) && !fresh;
                if (sameLine) {
                    // trailing comment: keep it on this line, before any pending line break
                    int keep = pending;
                    pending = -1;
                    Engine.this.emit(k);
                    pending = keep;
                } else {
                    int target = pending >= 0 ? pending : indent;
                    int next = skipComments(k + 1, to);
                    if (next < to && t.get(next).type() == Type.WORD && !nearDot(next) && clauseLength(next) > 0) {
                        target = base; // a comment that introduces the next clause sits with it
                    }
                    pending = -1;
                    newline(target);
                    Engine.this.emit(k);
                }
                boolean nextOnNewLine = k + 1 < n && newlinesBefore(k + 1) > 0;
                if (tk.isLineComment() || nextOnNewLine) {
                    if (pending < 0) {
                        pending = started ? contIndent() : base;
                    }
                }
            }
        }

        // ---------------------------------------------------------- output

        private void emit(int k) {
            SqlToken tk = t.get(k);
            if (pending >= 0) {
                int indent = pending;
                pending = -1;
                newline(indent);
            } else if (!fresh && needSpace(last, k)) {
                sb.append(' ');
            }
            outStart[k] = sb.length();
            sb.append(text(k));
            fresh = false;
            last = k;
        }

        private String text(int k) {
            SqlToken tk = t.get(k);
            if (tk.type() != Type.WORD || o.keywordCase() == KeywordCase.PRESERVE || nearDot(k)) {
                return tk.text();
            }
            String up = tk.text().toUpperCase(Locale.ROOT);
            if (!Keywords.RESERVED.contains(up) && !(recognised.contains(k) && Keywords.CONTEXTUAL.contains(up))) {
                return tk.text();
            }
            return o.keywordCase() == KeywordCase.UPPER ? up : tk.text().toLowerCase(Locale.ROOT);
        }

        /** Space between previous output token p and token c (both on the same line). */
        private boolean needSpace(int p, int c) {
            if (p < 0) {
                return false;
            }
            SqlToken a = t.get(p);
            SqlToken b = t.get(c);
            if (b.type() == Type.COMMA || b.type() == Type.SEMICOLON || b.type() == Type.RPAREN) {
                return false;
            }
            if (a.type() == Type.LPAREN || a.type() == Type.DOT || b.type() == Type.DOT) {
                return false;
            }
            if (a.type() == Type.COMMA || a.type() == Type.COMMENT) {
                return true;
            }
            return gap(p, c) > 0;
        }

        /** Whitespace between two output-adjacent tokens in the input (they may not be input-adjacent). */
        private int gap(int p, int c) {
            if (c == p + 1) {
                return t.get(c).start() - t.get(p).end();
            }
            return 1;
        }

        /**
         * Width of tokens [from, end) laid out on one line, or -1 if the range contains a line
         * comment. A subquery counts only up to its '(' since it becomes an indented block.
         */
        private int flatWidth(int from, int end) {
            int width = 0;
            for (int k = from; k < end; k++) {
                SqlToken tk = t.get(k);
                if (tk.type() == Type.COMMENT && tk.isLineComment()) {
                    return -1;
                }
                if (k > from && needSpace(k - 1, k)) {
                    width++;
                }
                width += tk.text().length();
                if (tk.type() == Type.LPAREN && match[k] > k) {
                    int inner = k + 1;
                    while (inner < match[k] && is(inner, Type.COMMENT)) {
                        inner++;
                    }
                    if (word(inner, "SELECT") || word(inner, "WITH")) {
                        return width;
                    }
                }
            }
            return width;
        }

        private void copy(int from, int to) {
            int regionStart = t.get(from).start();
            int outBase = sb.length();
            for (int k = from; k < to; k++) {
                outStart[k] = outBase + (t.get(k).start() - regionStart);
            }
            sb.append(src, regionStart, t.get(to - 1).end());
            fresh = false;
            last = to - 1;
        }

        private void write(String s) {
            sb.append(s);
            int nl = s.lastIndexOf('\n');
            if (nl >= 0) {
                lineStart = sb.length() - (s.length() - nl - 1);
            }
        }

        private void newline(int indent) {
            if (fresh) {
                sb.setLength(lineStart); // reuse the empty line instead of adding a blank one
            } else {
                trimTrailing();
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                lineStart = sb.length();
            }
            sb.append(" ".repeat(Math.max(0, indent)));
            fresh = true;
        }

        private void blankLine() {
            trimTrailing();
            sb.append("\n\n");
            lineStart = sb.length();
            fresh = true;
            pending = -1;
        }

        private void trimTrailing() {
            int len = sb.length();
            while (len > lineStart && sb.charAt(len - 1) == ' ') {
                len--;
            }
            sb.setLength(len);
        }

        private int col() {
            return sb.length() - lineStart;
        }

        private int lineIndent() {
            int i = lineStart;
            while (i < sb.length() && sb.charAt(i) == ' ') {
                i++;
            }
            return i - lineStart;
        }

        // ---------------------------------------------------------- token helpers

        private int newlinesBefore(int i) {
            int from = i == 0 ? 0 : t.get(i - 1).end();
            int count = 0;
            for (int p = from; p < t.get(i).start(); p++) {
                if (src.charAt(p) == '\n') {
                    count++;
                }
            }
            return count;
        }

        private boolean is(int k, Type type) {
            return k >= 0 && k < n && t.get(k).type() == type;
        }

        private boolean word(int k, String w) {
            return k >= 0 && k < n && t.get(k).isWord(w) && !nearDot(k);
        }

        private String upper(int k) {
            return t.get(k).text().toUpperCase(Locale.ROOT);
        }

        /** Part of a qualified name (t.col, schema.table): an identifier, never a keyword. */
        private boolean nearDot(int k) {
            return (k > 0 && is(k - 1, Type.DOT) && t.get(k - 1).end() == t.get(k).start())
                    || (k + 1 < n && is(k + 1, Type.DOT) && t.get(k).end() == t.get(k + 1).start());
        }

        private int skipComments(int from, int to) {
            int k = from;
            while (k < to && t.get(k).type() == Type.COMMENT) {
                k++;
            }
            return k;
        }

        private int prevSignificant(int k) {
            int p = k - 1;
            while (p >= 0 && t.get(p).type() == Type.COMMENT) {
                p--;
            }
            return p;
        }
    }
}
