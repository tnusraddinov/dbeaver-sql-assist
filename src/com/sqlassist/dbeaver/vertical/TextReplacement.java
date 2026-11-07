package com.sqlassist.dbeaver.vertical;

/** A single document edit: replace {@code length} characters at {@code offset} with {@code text}. */
public record TextReplacement(int offset, int length, String text) {
}
