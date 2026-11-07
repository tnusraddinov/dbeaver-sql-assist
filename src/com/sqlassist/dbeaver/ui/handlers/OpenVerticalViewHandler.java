package com.sqlassist.dbeaver.ui.handlers;

import com.sqlassist.dbeaver.parser.CrossReference;
import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.InsertStatementParser;
import com.sqlassist.dbeaver.ui.vertical.VerticalInsertDialog;
import com.sqlassist.dbeaver.vertical.InsertGrid;
import com.sqlassist.dbeaver.vertical.TextReplacement;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.text.BaseTextEditor;

import java.util.List;
import java.util.Optional;

/**
 * Module 2: opens the INSERT under the caret in {@link VerticalInsertDialog} and writes
 * the edits back as a single undoable change.
 */
public final class OpenVerticalViewHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        Shell shell = HandlerUtil.getActiveShell(event);
        if (!(BaseTextEditor.getTextEditor(HandlerUtil.getActiveEditor(event)) instanceof SQLEditorBase editor)) {
            return null;
        }
        TextViewer viewer = editor.getTextViewer();
        IDocument document = viewer == null ? null : viewer.getDocument();
        if (document == null) {
            return null;
        }
        String text = document.get();
        int caret = viewer.getSelectedRange().x;
        Optional<InsertStatement> statement = InsertStatementParser.findAt(text, caret);
        if (statement.isEmpty() || statement.get().rows().isEmpty()) {
            MessageDialog.openInformation(shell, "Vertical INSERT",
                    "Place the cursor inside an INSERT ... VALUES or INSERT ... SELECT statement.");
            return null;
        }
        if (!editor.isEditable()) {
            MessageDialog.openInformation(shell, "Vertical INSERT", "The editor is read-only.");
            return null;
        }

        InsertGrid grid = InsertGrid.of(statement.get(), text);
        Optional<CrossReference> ref = statement.get().crossReferenceAt(caret);
        int line = ref.map(CrossReference::columnIndex).orElse(-1);
        int row = ref.map(CrossReference::rowIndex).orElse(-1);

        VerticalInsertDialog dialog = new VerticalInsertDialog(shell, grid, line, row);
        if (dialog.open() != Window.OK || !grid.isModified()) {
            return null;
        }
        if (!text.equals(document.get())) {
            MessageDialog.openWarning(shell, "Vertical INSERT",
                    "The script changed while the dialog was open. No changes were applied.");
            return null;
        }
        apply(viewer, document, grid.replacements(text));
        return null;
    }

    private static void apply(TextViewer viewer, IDocument document, List<TextReplacement> replacements) {
        MultiTextEdit edit = new MultiTextEdit();
        for (TextReplacement r : replacements) {
            edit.addChild(new ReplaceEdit(r.offset(), r.length(), r.text()));
        }
        IRewriteTarget target = viewer.getRewriteTarget();
        target.beginCompoundChange(); // one Ctrl+Z undoes the whole dialog
        try {
            edit.apply(document);
        } catch (MalformedTreeException | BadLocationException e) {
            MessageDialog.openError(viewer.getTextWidget().getShell(), "Vertical INSERT",
                    "Could not apply changes: " + e.getMessage());
        } finally {
            target.endCompoundChange();
        }
    }
}
