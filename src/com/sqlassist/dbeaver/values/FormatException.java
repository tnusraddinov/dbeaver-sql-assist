package com.sqlassist.dbeaver.values;

/** Invalid JSON/XML; {@link #offset()} is the 0-based character position, or -1. */
public final class FormatException extends Exception {

    private final int offset;

    public FormatException(String message, int offset) {
        super(message);
        this.offset = offset;
    }

    public int offset() {
        return offset;
    }

    /** "line 3, column 7: message" for the status bar. */
    public String describe(String text) {
        if (offset < 0) {
            return getMessage();
        }
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return "Line " + line + ", column " + column + ": " + getMessage();
    }
}
