package com.sqlassist.dbeaver.ui.format;

import com.sqlassist.dbeaver.format.SqlFormatter;
import org.jkiss.dbeaver.model.DBPIdentifierCase;

/** Maps DBeaver's SQL formatting settings to {@link SqlFormatter.Options}. */
final class FormatterOptions {

    private FormatterOptions() {
    }

    /** DBeaver's "Keyword case" setting: UPPER, LOWER, or MIXED (= leave as typed). */
    static SqlFormatter.Options from(DBPIdentifierCase keywordCase, String indentString) {
        SqlFormatter.KeywordCase kc = keywordCase == null ? SqlFormatter.KeywordCase.UPPER : switch (keywordCase) {
            case UPPER -> SqlFormatter.KeywordCase.UPPER;
            case LOWER -> SqlFormatter.KeywordCase.LOWER;
            default -> SqlFormatter.KeywordCase.PRESERVE;
        };
        int indent = 4;
        if (indentString != null && !indentString.isEmpty() && indentString.chars().allMatch(c -> c == ' ')) {
            indent = indentString.length();
        }
        return new SqlFormatter.Options(kc, indent);
    }
}
