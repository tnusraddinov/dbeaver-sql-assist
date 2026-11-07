package com.sqlassist.dbeaver.validation;

import com.sqlassist.dbeaver.parser.Span;

/** A validation finding to underline in the editor. */
public record Problem(Span span, Severity severity, String message) {

    public enum Severity { ERROR, WARNING }
}
