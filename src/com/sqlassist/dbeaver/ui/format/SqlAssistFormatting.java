package com.sqlassist.dbeaver.ui.format;

import com.sqlassist.dbeaver.format.SqlFormatter;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;

/** Formatter options for an editor, taken from DBeaver's SQL settings (keyword case). */
public final class SqlAssistFormatting {

    private SqlAssistFormatting() {
    }

    public static SqlFormatter.Options options(SQLEditorBase editor) {
        try {
            return FormatterOptions.from(editor.getSyntaxManager().getKeywordCase(), "    ");
        } catch (RuntimeException e) {
            return SqlFormatter.Options.DEFAULT;
        }
    }
}
