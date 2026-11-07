package com.sqlassist.dbeaver.ui.handlers;

import com.sqlassist.dbeaver.parser.CrossReference;
import com.sqlassist.dbeaver.parser.InsertStatementParser;
import com.sqlassist.dbeaver.parser.Span;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.text.BaseTextEditor;

import java.util.List;
import java.util.Optional;

/**
 * Jumps from an INSERT column to its value and from a value back to its column.
 * From a column, the value in the first VALUES tuple is selected.
 */
public final class SelectCounterpartHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IEditorPart part = HandlerUtil.getActiveEditor(event);
        if (!(BaseTextEditor.getTextEditor(part) instanceof SQLEditorBase editor)) {
            return null;
        }
        TextViewer viewer = editor.getTextViewer();
        IDocument document = viewer == null ? null : viewer.getDocument();
        if (document == null) {
            return null;
        }
        int caret = viewer.getSelectedRange().x;
        Optional<CrossReference> ref = InsertStatementParser.findAt(document.get(), caret)
                .flatMap(stmt -> stmt.crossReferenceAt(caret));
        if (ref.isEmpty()) {
            return null;
        }
        List<Span> targets = ref.get().targets();
        if (targets.isEmpty()) {
            return null;
        }
        Span target = pickTarget(targets, caret);
        viewer.setSelectedRange(target.start(), target.length());
        viewer.revealRange(target.start(), target.length());
        return null;
    }

    /** First target after the caret, else the first one (a column always precedes its values). */
    private static Span pickTarget(List<Span> targets, int caret) {
        for (Span s : targets) {
            if (s.start() > caret) {
                return s;
            }
        }
        return targets.get(0);
    }
}
