package com.sqlassist.dbeaver.ui.handlers;

import com.sqlassist.dbeaver.ui.hints.ColumnHintUpdater;
import com.sqlassist.dbeaver.ui.hints.HintPreferences;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.text.BaseTextEditor;

/** Module 4: switches the inline column hints on or off in all SQL editors. */
public final class ToggleColumnHintsHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        HintPreferences.setEnabled(!HintPreferences.isEnabled());
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference ref : page.getEditorReferences()) {
                    if (BaseTextEditor.getTextEditor(ref.getEditor(false)) instanceof SQLEditorBase editor) {
                        ColumnHintUpdater.refresh(editor);
                    }
                }
            }
        }
        return null;
    }
}
