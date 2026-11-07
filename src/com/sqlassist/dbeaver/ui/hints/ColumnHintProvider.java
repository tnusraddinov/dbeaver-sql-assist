package com.sqlassist.dbeaver.ui.hints;

import com.sqlassist.dbeaver.hints.ColumnHints;
import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.InsertStatementParser;
import com.sqlassist.dbeaver.parser.SqlTokenizer;
import com.sqlassist.dbeaver.ui.validation.TableMetadataResolver;
import com.sqlassist.dbeaver.validation.TableMeta;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Module 4: code-mining provider registered for DBeaver SQL editors via
 * {@code org.eclipse.ui.workbench.texteditor.codeMiningProviders}. DBeaver's editor
 * installs registered providers itself; {@link ColumnHintUpdater} asks for a refresh
 * after edits.
 */
public final class ColumnHintProvider extends AbstractCodeMiningProvider {

    /** Above this size only the visible part of the script (plus a margin) gets hints. */
    static final int LARGE_DOCUMENT = 300_000;
    private static final int VISIBLE_MARGIN = 20_000;
    private static final int MAX_HINTS = 5_000;

    @Override
    public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(ITextViewer viewer, IProgressMonitor monitor) {
        if (!HintPreferences.isEnabled() || viewer.getDocument() == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        // Snapshot everything UI-bound here; the rest runs off the UI thread.
        String text = viewer.getDocument().get();
        int[] range = range(viewer, text);
        SQLEditorBase editor = getAdapter(ITextEditor.class) instanceof SQLEditorBase e ? e : null;
        DBCExecutionContext context = editor == null ? null : editor.getExecutionContext();
        boolean showTypes = HintPreferences.showTypes();

        return CompletableFuture.supplyAsync(() -> {
            List<InsertStatement> statements = range == null
                    ? InsertStatementParser.parseAll(text)
                    : InsertStatementParser.parseAll(SqlTokenizer.tokenize(text, range[0], range[1]), text);
            boolean connected = context != null && context.isConnected();
            Map<String, Optional<TableMeta>> tables = new HashMap<>();
            List<ICodeMining> minings = new ArrayList<>();
            for (InsertStatement statement : statements) {
                if (monitor.isCanceled() || minings.size() >= MAX_HINTS) {
                    break;
                }
                TableMeta table = null;
                // Metadata is only needed for types, or for names when there is no column list.
                if (connected && (showTypes || !statement.hasColumnList())) {
                    table = tables.computeIfAbsent(statement.tableName(), name -> TableMetadataResolver.shared()
                            .resolve(new DefaultProgressMonitor(monitor), context, name)).orElse(null);
                }
                for (ColumnHints.Hint hint : ColumnHints.compute(statement, text, table, showTypes)) {
                    minings.add(new ColumnHintMining(new Position(hint.offset(), 1), hint.label(), this));
                }
            }
            return minings;
        });
    }

    /** {from, to} of the region to scan, or {@code null} for the whole (small) document. */
    private static int[] range(ITextViewer viewer, String text) {
        int length = text.length();
        if (length <= LARGE_DOCUMENT || viewer.getTextWidget() == null
                || viewer.getTextWidget().getDisplay().getThread() != Thread.currentThread()) {
            return null;
        }
        int top = viewer.getTopIndexStartOffset();
        int bottom = viewer.getBottomIndexEndOffset();
        int from = Math.max(0, top - VISIBLE_MARGIN);
        int to = Math.min(length, bottom + VISIBLE_MARGIN);
        while (from > 0 && text.charAt(from - 1) != '\n') {
            from--;
        }
        return new int[] {from, to};
    }
}
