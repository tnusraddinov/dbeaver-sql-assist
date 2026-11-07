package com.sqlassist.dbeaver.ui;

import com.sqlassist.dbeaver.ui.highlight.CrossHighlighter;
import com.sqlassist.dbeaver.ui.hints.ColumnHintUpdater;
import com.sqlassist.dbeaver.ui.validation.LiveValidator;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.text.BaseTextEditor;

/**
 * Hooks every SQL editor (already open or opened later) and installs the per-editor
 * add-ons: {@link CrossHighlighter} (Module 1), {@link LiveValidator} (Module 3) and
 * {@link ColumnHintUpdater} (Module 4).
 */
public final class SqlAssistStartup implements IStartup {

    @Override
    public void earlyStartup() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().asyncExec(() -> {
            PartTracker tracker = new PartTracker();
            workbench.addWindowListener(tracker);
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
                tracker.windowOpened(window);
            }
        });
    }

    private static final class PartTracker implements IWindowListener, IPartListener2 {

        @Override
        public void windowOpened(IWorkbenchWindow window) {
            window.getPartService().addPartListener(this);
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference ref : page.getEditorReferences()) {
                    attach(ref.getEditor(false));
                }
            }
        }

        @Override
        public void windowClosed(IWorkbenchWindow window) {
            window.getPartService().removePartListener(this);
        }

        @Override
        public void windowActivated(IWorkbenchWindow window) {
        }

        @Override
        public void windowDeactivated(IWorkbenchWindow window) {
        }

        @Override
        public void partOpened(IWorkbenchPartReference ref) {
            attach(ref.getPart(false));
        }

        @Override
        public void partActivated(IWorkbenchPartReference ref) {
            attach(ref.getPart(false)); // covers lazily restored editors
        }

        @Override
        public void partVisible(IWorkbenchPartReference ref) {
            attach(ref.getPart(false));
        }

        private static void attach(IWorkbenchPart part) {
            if (!(part instanceof IEditorPart editorPart)) {
                return;
            }
            BaseTextEditor textEditor = BaseTextEditor.getTextEditor(editorPart);
            if (textEditor instanceof SQLEditorBase sqlEditor) {
                CrossHighlighter.install(sqlEditor);
                LiveValidator.install(sqlEditor);
                ColumnHintUpdater.install(sqlEditor);
            }
        }
    }
}
