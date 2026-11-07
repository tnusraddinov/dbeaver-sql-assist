package com.sqlassist.dbeaver.parser;

/**
 * Half-open character range {@code [start, end)} inside the document.
 */
public record Span(int start, int end) {

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid span [" + start + ", " + end + ")");
        }
    }

    public int length() {
        return end - start;
    }

    /** Caret-friendly containment: a caret placed right after the last character still counts. */
    public boolean containsCaret(int offset) {
        return offset >= start && offset <= end;
    }

    public String text(CharSequence source) {
        return source.subSequence(start, end).toString();
    }
}
