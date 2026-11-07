package com.sqlassist.dbeaver.parser;

/**
 * Lexical token. Comments and whitespace are never emitted.
 */
public record SqlToken(Type type, int start, int end, String text) {

    public enum Type {
        WORD,          // keyword, identifier, bind parameter ($1, :name, @var)
        QUOTED_IDENT,  // "x", `x`, [x]
        STRING,        // 'x', E'x', N'x', $$x$$, $tag$x$tag$
        NUMBER,
        LPAREN,
        RPAREN,
        COMMA,
        SEMICOLON,
        DOT,
        OTHER          // operators and anything else
    }

    public boolean isWord(String keyword) {
        return type == Type.WORD && text.equalsIgnoreCase(keyword);
    }

    public boolean isIdentifier() {
        return type == Type.WORD || type == Type.QUOTED_IDENT;
    }

    public Span span() {
        return new Span(start, end);
    }
}
