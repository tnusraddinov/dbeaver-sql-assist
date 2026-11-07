package com.sqlassist.dbeaver.ui.format;

import com.sqlassist.dbeaver.format.SqlFormatException;
import com.sqlassist.dbeaver.format.SqlFormatter;
import org.jkiss.dbeaver.model.sql.format.SQLFormatter;
import org.jkiss.dbeaver.model.sql.format.SQLFormatterConfiguration;

/**
 * Makes the formatter selectable in DBeaver (Preferences → Editors → SQL Editor → Formatting),
 * so DBeaver's own Format SQL command (Ctrl+Shift+F) can use it too.
 */
public final class DataGripStyleFormatter implements SQLFormatter {

    @Override
    public String format(String source, SQLFormatterConfiguration configuration) {
        try {
            SqlFormatter.Options options = FormatterOptions.from(configuration.getKeywordCase(),
                    configuration.getIndentString());
            String formatted = SqlFormatter.format(source, options).text();
            // DBeaver replaces exactly the text it passed in: keep its trailing line break.
            return source.endsWith("\n") && !formatted.endsWith("\n") ? formatted + "\n" : formatted;
        } catch (SqlFormatException | RuntimeException e) {
            return source; // never damage the script: an unsafe result is not used
        }
    }
}
