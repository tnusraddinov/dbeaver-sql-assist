package com.sqlassist.dbeaver.ui.values;

import com.sqlassist.dbeaver.parser.CrossReference;
import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.InsertStatementParser;
import com.sqlassist.dbeaver.values.LongValues;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;

import java.util.Optional;

/** Module 5: opens the string value at an offset in {@link ValueEditorDialog} and writes it back. */
public final class ValueEditing {

    private static final String TITLE = "Value Editor";

    private ValueEditing() {
    }

    public static void openAt(SQLEditorBase editor, int offset) {
        TextViewer viewer = editor.getTextViewer();
        IDocument document = viewer == null ? null : viewer.getDocument();
        if (document == null) {
            return;
        }
        Shell shell = viewer.getTextWidget().getShell();
        String text = document.get();
        Optional<InsertStatement> statement = InsertStatementParser.findAt(text, offset);
        Optional<LongValues.Value> value = statement.flatMap(s -> LongValues.at(s, text, offset));
        if (value.isEmpty()) {
            MessageDialog.openInformation(shell, TITLE,
                    "Place the cursor on a string value ('...') inside an INSERT statement.");
            return;
        }
        LongValues.Value v = value.get();
        String column = statement.get().crossReferenceAt(v.span().start())
                .filter(r -> r.kind() == CrossReference.Kind.VALUE)
                .map(r -> statement.get().hasColumnList()
                        ? statement.get().columns().get(r.columnIndex()).text(text)
                        : "value #" + (r.columnIndex() + 1))
                .orElse("value");

        ValueEditorDialog dialog = new ValueEditorDialog(shell, TITLE + " - " + statement.get().tableName() + "." + column,
                v.content(), v.kind(), editor.isEditable());
        if (dialog.open() != Window.OK || dialog.result() == null || dialog.result().equals(v.content())) {
            return;
        }
        if (!text.equals(document.get())) {
            MessageDialog.openWarning(shell, TITLE, "The script changed while the editor was open. Nothing was applied.");
            return;
        }
        String literal = v.literal().render(dialog.result());
        IRewriteTarget target = viewer.getRewriteTarget();
        target.beginCompoundChange();
        try {
            document.replace(v.span().start(), v.span().length(), literal);
            viewer.setSelectedRange(v.span().start(), literal.length());
            viewer.revealRange(v.span().start(), literal.length());
        } catch (BadLocationException e) {
            MessageDialog.openError(shell, TITLE, "Could not apply the value: " + e.getMessage());
        } finally {
            target.endCompoundChange();
        }
    }
}
