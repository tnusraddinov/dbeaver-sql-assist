package com.sqlassist.dbeaver.ui.handlers;

import com.sqlassist.dbeaver.format.SqlFormatException;
import com.sqlassist.dbeaver.format.SqlFormatter;
import com.sqlassist.dbeaver.parser.SqlToken;
import com.sqlassist.dbeaver.parser.SqlTokenizer;
import com.sqlassist.dbeaver.ui.format.SqlAssistFormatting;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.text.BaseTextEditor;

import java.util.List;

/**
 * Formats the selected lines, or the whole script when nothing is selected (like DataGrip's
 * Reformat Code). The edit is minimal (unchanged prefix/suffix are kept), undoable in one step,
 * and keeps the caret on the same token.
 */
public final class FormatSqlHandler extends AbstractHandler {

    private static final String TITLE = "Format SQL";

    @Override
    public Object execute(ExecutionEvent event) {
        if (!(BaseTextEditor.getTextEditor(HandlerUtil.getActiveEditor(event)) instanceof SQLEditorBase editor)) {
            return null;
        }
        TextViewer viewer = editor.getTextViewer();
        IDocument document = viewer == null ? null : viewer.getDocument();
        if (document == null) {
            return null;
        }
        Shell shell = viewer.getTextWidget().getShell();
        if (!editor.isEditable()) {
            MessageDialog.openInformation(shell, TITLE, "The editor is read-only.");
            return null;
        }
        String text = document.get();
        Point selection = viewer.getSelectedRange();
        boolean hasSelection = selection.y > 0;
        int from = 0;
        int to = text.length();
        String prefix = "";
        if (hasSelection) {
            int[] region = lineRegion(text, selection.x, selection.x + selection.y);
            from = region[0];
            to = region[1];
            prefix = leadingWhitespace(text, from);
        }
        String region = text.substring(from, to);
        String delimiter = TextUtilities.getDefaultLineDelimiter(document);

        SqlFormatter.Result result;
        try {
            result = SqlFormatter.format(region, SqlAssistFormatting.options(editor), prefix);
        } catch (SqlFormatException e) {
            MessageDialog.openWarning(shell, TITLE,
                    "SQL Assist could not format this SQL safely, so nothing was changed.\n\n" + e.getMessage());
            return null;
        }
        String formatted = result.text();
        if (!hasSelection && region.endsWith("\n") && !formatted.isEmpty()) {
            formatted += "\n";
        }
        int caretInRegion = Math.max(0, Math.min(region.length(), selection.x - from));
        int newCaret = result.mapOffset(region, caretInRegion);
        if (!"\n".equals(delimiter)) {
            newCaret += countNewlines(formatted, newCaret) * (delimiter.length() - 1);
            formatted = formatted.replace("\n", delimiter);
        }
        if (formatted.equals(region)) {
            return null;
        }
        replaceMinimal(viewer, document, from, region, formatted);
        if (hasSelection) {
            viewer.setSelectedRange(from, formatted.length());
        } else {
            viewer.setSelectedRange(from + Math.min(newCaret, formatted.length()), 0);
        }
        viewer.revealRange(viewer.getSelectedRange().x, 0);
        return null;
    }

    /** Whole lines covering [start, end), widened so no string or comment is cut in half. */
    private static int[] lineRegion(String text, int start, int end) {
        if (end > start && end <= text.length() && text.charAt(end - 1) == '\n') {
            end--; // selection ending at a line start does not include that line
        }
        int from = lineStart(text, start);
        int to = lineEnd(text, end);
        List<SqlToken> tokens = SqlTokenizer.tokenizeWithComments(text);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (SqlToken t : tokens) {
                if (t.start() < from && t.end() > from) {
                    from = lineStart(text, t.start());
                    changed = true;
                }
                if (t.start() < to && t.end() > to) {
                    to = lineEnd(text, t.end());
                    changed = true;
                }
            }
        }
        return new int[] {from, to};
    }

    private static int lineStart(String text, int offset) {
        int p = Math.min(offset, text.length());
        while (p > 0 && text.charAt(p - 1) != '\n') {
            p--;
        }
        return p;
    }

    private static int lineEnd(String text, int offset) {
        int p = Math.max(0, offset);
        while (p < text.length() && text.charAt(p) != '\n' && text.charAt(p) != '\r') {
            p++;
        }
        return p;
    }

    private static String leadingWhitespace(String text, int lineStart) {
        int p = lineStart;
        while (p < text.length() && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) {
            p++;
        }
        return text.substring(lineStart, p);
    }

    private static int countNewlines(String s, int upTo) {
        int count = 0;
        for (int i = 0; i < upTo && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** Replaces only the part that differs, so markers and folding elsewhere are left alone. */
    private static void replaceMinimal(TextViewer viewer, IDocument document, int offset, String oldText, String newText) {
        int prefix = 0;
        int max = Math.min(oldText.length(), newText.length());
        while (prefix < max && oldText.charAt(prefix) == newText.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix
                && oldText.charAt(oldText.length() - 1 - suffix) == newText.charAt(newText.length() - 1 - suffix)) {
            suffix++;
        }
        IRewriteTarget target = viewer.getRewriteTarget();
        target.beginCompoundChange();
        try {
            document.replace(offset + prefix, oldText.length() - prefix - suffix,
                    newText.substring(prefix, newText.length() - suffix));
        } catch (BadLocationException e) {
            MessageDialog.openError(viewer.getTextWidget().getShell(), TITLE, "Could not apply formatting: " + e.getMessage());
        } finally {
            target.endCompoundChange();
        }
    }
}
