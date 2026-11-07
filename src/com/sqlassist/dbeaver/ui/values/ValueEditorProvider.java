package com.sqlassist.dbeaver.ui.values;

import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.InsertStatementParser;
import com.sqlassist.dbeaver.parser.SqlTokenizer;
import com.sqlassist.dbeaver.values.LongValues;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineContentCodeMining;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Module 5: draws a clickable "✎ JSON" / "✎ XML" / "✎ 240 chars" link right after long
 * INSERT string values; clicking it opens {@link ValueEditorDialog}.
 */
public final class ValueEditorProvider extends AbstractCodeMiningProvider {

    private static final int LARGE_DOCUMENT = 300_000;
    private static final int MAX_LINKS = 2_000;

    @Override
    public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(ITextViewer viewer, IProgressMonitor monitor) {
        SQLEditorBase editor = getAdapter(ITextEditor.class) instanceof SQLEditorBase e ? e : null;
        if (editor == null || viewer.getDocument() == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        String text = viewer.getDocument().get();
        int[] range = visibleRange(viewer, text);
        return CompletableFuture.supplyAsync(() -> {
            List<InsertStatement> statements = range == null ? InsertStatementParser.parseAll(text)
                    : InsertStatementParser.parseAll(SqlTokenizer.tokenize(text, range[0], range[1]), text);
            List<ICodeMining> minings = new ArrayList<>();
            for (InsertStatement statement : statements) {
                for (LongValues.Value value : LongValues.find(statement, text)) {
                    if (monitor.isCanceled() || minings.size() >= MAX_LINKS) {
                        return minings;
                    }
                    int end = value.span().end();
                    if (end < text.length()) {
                        minings.add(new EditLink(new Position(end, 1), linkLabel(value), this, editor));
                    }
                }
            }
            return minings;
        });
    }

    private static String linkLabel(LongValues.Value value) {
        return switch (value.kind()) {
            case JSON -> " ✎ JSON";
            case XML -> " ✎ XML";
            case TEXT -> " ✎ " + value.content().length() + " chars";
        };
    }

    private static int[] visibleRange(ITextViewer viewer, String text) {
        if (text.length() <= LARGE_DOCUMENT || viewer.getTextWidget() == null
                || viewer.getTextWidget().getDisplay().getThread() != Thread.currentThread()) {
            return null;
        }
        int from = Math.max(0, viewer.getTopIndexStartOffset() - 20_000);
        int to = Math.min(text.length(), viewer.getBottomIndexEndOffset() + 20_000);
        while (from > 0 && text.charAt(from - 1) != '\n') {
            from--;
        }
        return new int[] {from, to};
    }

    /** Drawn just before the character after the value (its comma or closing parenthesis). */
    private static final class EditLink extends LineContentCodeMining {

        private final SQLEditorBase editor;

        EditLink(Position position, String label, ICodeMiningProvider provider, SQLEditorBase editor) {
            super(position, provider);
            setLabel(label);
            this.editor = editor;
        }

        /** Non-null action makes the label clickable (hand cursor). */
        @Override
        public Consumer<MouseEvent> getAction() {
            // The position follows edits, so it still marks the value's end when clicked.
            return e -> ValueEditing.openAt(editor, getPosition().getOffset());
        }

        @Override
        public Point draw(GC gc, StyledText textWidget, Color color, int x, int y) {
            Color link = JFaceResources.getColorRegistry().get(JFacePreferences.HYPERLINK_COLOR);
            return super.draw(gc, textWidget,
                    link != null ? link : textWidget.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND), x, y);
        }
    }
}
