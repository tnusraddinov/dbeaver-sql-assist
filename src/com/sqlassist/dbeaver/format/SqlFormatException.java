package com.sqlassist.dbeaver.format;

/** The formatter refused to produce output (its safety check failed). */
public final class SqlFormatException extends Exception {

    public SqlFormatException(String message) {
        super(message);
    }
}
