package com.sqlassist.dbeaver.parser;

/**
 * Lexical token. Whitespace is never emitted; comments only on request.
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
        COMMENT,       // only with SqlTokenizer.tokenizeWithComments: -- line or /* block */
        OTHER          // operators and anything else
    }

    public boolean isWord(String keyword) {
        return type == Type.WORD && text.equalsIgnoreCase(keyword);
    }

    public boolean isIdentifier() {
        return type == Type.WORD || type == Type.QUOTED_IDENT;
    }

    public boolean isLineComment() {
        return type == Type.COMMENT && text.startsWith("--");
    }

    public Span span() {
        return new Span(start, end);
    }
}
