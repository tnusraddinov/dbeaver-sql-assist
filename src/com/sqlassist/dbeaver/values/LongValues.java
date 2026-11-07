package com.sqlassist.dbeaver.values;

import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.Span;
import com.sqlassist.dbeaver.parser.SqlToken;
import com.sqlassist.dbeaver.parser.SqlTokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Module 5: finds INSERT string values worth opening in the value editor
 * (JSON, XML, long or multi-line text).
 */
public final class LongValues {

    /** JSON/XML shorter than this on one line is readable as is. */
    public static final int STRUCTURED_MIN_LENGTH = 30;
    /** Plain text gets an icon from this length, or when it spans lines. */
    public static final int TEXT_MIN_LENGTH = 80;

    /** A string literal inside an INSERT and what it contains. */
    public record Value(Span span, StringLiteral literal, ValueKind kind) {
        public String content() {
            return literal.content();
        }
    }

    private LongValues() {
    }

    /** Values that should get an editor icon. */
    public static List<Value> find(InsertStatement statement, CharSequence text) {
        List<Value> result = new ArrayList<>();
        for (List<Span> row : statement.rows()) {
            for (Span span : row) {
                literalAt(span, text).filter(LongValues::isLong).ifPresent(result::add);
            }
        }
        return result;
    }

    /** The string value under {@code offset}, of any length (for the keyboard command). */
    public static Optional<Value> at(InsertStatement statement, CharSequence text, int offset) {
        for (List<Span> row : statement.rows()) {
            for (Span span : row) {
                if (span.containsCaret(offset)) {
                    return literalAt(span, text);
                }
            }
        }
        return Optional.empty();
    }

    static boolean isLong(Value v) {
        String c = v.content();
        boolean multiLine = c.indexOf('\n') >= 0;
        return switch (v.kind()) {
            case JSON, XML -> multiLine || c.length() >= STRUCTURED_MIN_LENGTH;
            case TEXT -> multiLine || c.length() >= TEXT_MIN_LENGTH;
        };
    }

    private static Optional<Value> literalAt(Span span, CharSequence text) {
        if (span.length() == 0) {
            return Optional.empty();
        }
        String raw = span.text(text);
        List<SqlToken> tokens = SqlTokenizer.tokenize(raw);
        if (tokens.size() != 1 || tokens.get(0).type() != SqlToken.Type.STRING) {
            return Optional.empty(); // expressions like 'a' || 'b' or '{}'::jsonb are left alone
        }
        return StringLiteral.parse(raw).map(l -> new Value(span, l, ValueKind.detect(l.content())));
    }
}
