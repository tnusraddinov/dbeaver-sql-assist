package com.sqlassist.dbeaver.ui.hints;

import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.IViewportListener;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.source.ISourceViewerExtension5;
import org.eclipse.swt.custom.StyledText;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;

/**
 * Code minings are only recomputed when someone calls {@code updateCodeMinings()}.
 * This asks for a refresh after typing pauses, after scrolling in large scripts
 * (only the visible part is mined there) and when the connection or schema changes
 * (types become available or stale).
 */
public final class ColumnHintUpdater implements ITextListener, IViewportListener {

    private static final String WIDGET_KEY = ColumnHintUpdater.class.getName();
    private static final int TYPING_DELAY_MS = 500;
    private static final int SCROLL_DELAY_MS = 300;
    private static final int CONTEXT_POLL_MS = 3_000;

    private final SQLEditorBase editor;
    private final TextViewer viewer;
    private final Runnable refresh = this::refreshNow;
    private final Runnable poll = this::pollContext;
    private DBCExecutionContext lastContext;
    private boolean disposed;

    private ColumnHintUpdater(SQLEditorBase editor, TextViewer viewer) {
        this.editor = editor;
        this.viewer = viewer;
    }

    public static void install(SQLEditorBase editor) {
        TextViewer viewer = editor.getTextViewer();
        if (!(viewer instanceof ISourceViewerExtension5) || viewer.getTextWidget() == null
                || viewer.getTextWidget().isDisposed() || viewer.getTextWidget().getData(WIDGET_KEY) != null) {
            return;
        }
        ColumnHintUpdater updater = new ColumnHintUpdater(editor, viewer);
        StyledText widget = viewer.getTextWidget();
        widget.setData(WIDGET_KEY, updater);
        widget.addDisposeListener(e -> updater.dispose());
        viewer.addTextListener(updater);
        viewer.addViewportListener(updater);
        updater.lastContext = editor.getExecutionContext();
        updater.schedule(TYPING_DELAY_MS);
        widget.getDisplay().timerExec(CONTEXT_POLL_MS, updater.poll);
    }

    /** Refreshes every open SQL editor, e.g. after toggling the hints. */
    public static void refresh(SQLEditorBase editor) {
        TextViewer viewer = editor.getTextViewer();
        if (viewer != null && viewer.getTextWidget() != null
                && viewer.getTextWidget().getData(WIDGET_KEY) instanceof ColumnHintUpdater updater) {
            updater.refreshNow();
        }
    }

    @Override
    public void textChanged(TextEvent event) {
        schedule(TYPING_DELAY_MS);
    }

    @Override
    public void viewportChanged(int verticalOffset) {
        if (viewer.getDocument() != null && viewer.getDocument().getLength() > ColumnHintProvider.LARGE_DOCUMENT) {
            schedule(SCROLL_DELAY_MS);
        }
    }

    private void schedule(int delayMs) {
        StyledText widget = viewer.getTextWidget();
        if (!disposed && widget != null && !widget.isDisposed()) {
            widget.getDisplay().timerExec(delayMs, refresh);
        }
    }

    private void pollContext() {
        if (disposed) {
            return;
        }
        DBCExecutionContext context = editor.getExecutionContext();
        if (context != lastContext) {
            lastContext = context;
            refreshNow();
        }
        viewer.getTextWidget().getDisplay().timerExec(CONTEXT_POLL_MS, poll);
    }

    private void refreshNow() {
        if (!disposed && ((ISourceViewerExtension5) viewer).hasCodeMiningProviders()) {
            ((ISourceViewerExtension5) viewer).updateCodeMinings();
        }
    }

    private void dispose() {
        disposed = true;
        viewer.removeTextListener(this);
        viewer.removeViewportListener(this);
        StyledText widget = viewer.getTextWidget();
        if (widget != null && !widget.isDisposed()) {
            widget.getDisplay().timerExec(-1, refresh);
            widget.getDisplay().timerExec(-1, poll);
        }
    }
}
