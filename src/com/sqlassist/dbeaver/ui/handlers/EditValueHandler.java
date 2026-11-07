package com.sqlassist.dbeaver.ui.handlers;

import com.sqlassist.dbeaver.ui.values.ValueEditing;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.text.BaseTextEditor;

/** Module 5: opens the string value under the caret in the value editor. */
public final class EditValueHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        if (BaseTextEditor.getTextEditor(HandlerUtil.getActiveEditor(event)) instanceof SQLEditorBase editor
                && editor.getTextViewer() != null) {
            ValueEditing.openAt(editor, editor.getTextViewer().getSelectedRange().x);
        }
        return null;
    }
}
