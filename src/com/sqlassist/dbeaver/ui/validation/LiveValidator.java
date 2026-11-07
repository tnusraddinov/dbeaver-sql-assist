package com.sqlassist.dbeaver.ui.validation;

import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.InsertStatementParser;
import com.sqlassist.dbeaver.parser.SqlTokenizer;
import com.sqlassist.dbeaver.validation.InsertValidator;
import com.sqlassist.dbeaver.validation.Problem;
import com.sqlassist.dbeaver.validation.TableMeta;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.swt.custom.StyledText;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Module 3: re-validates INSERT statements against the connected database shortly after
 * the user stops typing, and when the editor's connection or schema changes.
 * Parsing and metadata lookups run in a background job; annotations are applied on the
 * UI thread only if the document has not changed in the meantime.
 */
public final class LiveValidator implements ITextListener {

    private static final String WIDGET_KEY = LiveValidator.class.getName();
    private static final int TYPING_DELAY_MS = 700;
    private static final int CONTEXT_POLL_MS = 3_000;
    private static final int MAX_STATEMENTS = 500;
    private static final int WINDOW_RADIUS = 200_000;

    private final SQLEditorBase editor;
    private final TextViewer viewer;
    private final TableMetadataResolver resolver = TableMetadataResolver.shared();
    private final Runnable trigger = this::startValidation;
    private final Runnable poll = this::pollContext;
    private Annotation[] current = new Annotation[0];
    private DBCExecutionContext lastContext;
    private Job job;
    private boolean disposed;

    private LiveValidator(SQLEditorBase editor, TextViewer viewer) {
        this.editor = editor;
        this.viewer = viewer;
    }

    /** Installs once per editor; calling again just schedules a re-check (e.g. on activation). */
    public static void install(SQLEditorBase editor) {
        TextViewer viewer = editor.getTextViewer();
        if (viewer == null || viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed()) {
            return;
        }
        StyledText widget = viewer.getTextWidget();
        if (widget.getData(WIDGET_KEY) instanceof LiveValidator existing) {
            existing.schedule(TYPING_DELAY_MS);
            return;
        }
        LiveValidator validator = new LiveValidator(editor, viewer);
        widget.setData(WIDGET_KEY, validator);
        widget.addDisposeListener(e -> validator.dispose());
        viewer.addTextListener(validator);
        validator.schedule(TYPING_DELAY_MS);
        widget.getDisplay().timerExec(CONTEXT_POLL_MS, validator.poll);
    }

    @Override
    public void textChanged(TextEvent event) {
        schedule(TYPING_DELAY_MS);
    }

    private void schedule(int delayMs) {
        StyledText widget = viewer.getTextWidget();
        if (!disposed && widget != null && !widget.isDisposed()) {
            widget.getDisplay().timerExec(delayMs, trigger); // re-arming resets the delay
        }
    }

    /** Connect, disconnect, switching connection or schema all replace the execution context. */
    private void pollContext() {
        if (disposed) {
            return;
        }
        if (editor.getExecutionContext() != lastContext) {
            startValidation();
        }
        viewer.getTextWidget().getDisplay().timerExec(CONTEXT_POLL_MS, poll);
    }

    private void startValidation() {
        if (disposed) {
            return;
        }
        DBCExecutionContext context = editor.getExecutionContext();
        lastContext = context;
        IDocument document = viewer.getDocument();
        if (document == null || context == null || !context.isConnected()) {
            cancelJob();
            replace(Map.of());
            return;
        }
        cancelJob();
        job = new ValidationJob(document.get(), stamp(document), viewer.getSelectedRange().x, context);
        job.schedule();
    }

    private void cancelJob() {
        if (job != null) {
            job.cancel();
            job = null;
        }
    }

    private static long stamp(IDocument document) {
        return document instanceof IDocumentExtension4 ext
                ? ext.getModificationStamp() : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    }

    private void apply(List<Problem> problems, long docStamp, Job finished) {
        IDocument document = viewer.getDocument();
        if (disposed || finished != job || document == null || stamp(document) != docStamp) {
            return; // a newer run is pending
        }
        Map<Annotation, Position> added = new HashMap<>();
        for (Problem p : problems) {
            if (p.span().length() > 0 && p.span().end() <= document.getLength()) {
                added.put(new ValidationAnnotation(p), new Position(p.span().start(), p.span().length()));
            }
        }
        replace(added);
    }

    private void replace(Map<Annotation, Position> added) {
        if (current.length == 0 && added.isEmpty()) {
            return;
        }
        IAnnotationModel model = editor.getAnnotationModel();
        if (model == null) {
            return;
        }
        if (model instanceof IAnnotationModelExtension ext) {
            ext.replaceAnnotations(current, added);
        } else {
            for (Annotation a : current) {
                model.removeAnnotation(a);
            }
            added.forEach(model::addAnnotation);
        }
        current = added.keySet().toArray(new Annotation[0]);
    }

    private void dispose() {
        if (disposed) {
            return;
        }
        cancelJob();
        replace(Map.of());
        disposed = true;
        viewer.removeTextListener(this);
        StyledText widget = viewer.getTextWidget();
        if (widget != null && !widget.isDisposed()) {
            widget.getDisplay().timerExec(-1, trigger);
            widget.getDisplay().timerExec(-1, poll);
        }
    }

    private final class ValidationJob extends Job {
        private final String text;
        private final long docStamp;
        private final int caret;
        private final DBCExecutionContext context;

        ValidationJob(String text, long docStamp, int caret, DBCExecutionContext context) {
            super("SQL Assist: validating INSERT statements");
            this.text = text;
            this.docStamp = docStamp;
            this.caret = caret;
            this.context = context;
            setSystem(true);
            setPriority(Job.DECORATE);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            List<Problem> problems = new ArrayList<>();
            try {
                DBRProgressMonitor dbMonitor = new DefaultProgressMonitor(monitor);
                for (InsertStatement statement : statementsNearCaret()) {
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    Optional<TableMeta> table = resolver.resolve(dbMonitor, context, statement.tableName());
                    table.ifPresent(t -> problems.addAll(InsertValidator.validate(statement, text, t)));
                }
            } catch (RuntimeException e) {
                return Status.OK_STATUS; // never disturb the user because of a validation bug
            }
            if (!monitor.isCanceled()) {
                StyledText widget = viewer.getTextWidget();
                if (widget != null && !widget.isDisposed()) {
                    widget.getDisplay().asyncExec(() -> apply(problems, docStamp, this));
                }
            }
            return Status.OK_STATUS;
        }

        /** Large scripts (dumps) are checked only around the caret, closest statements first. */
        private List<InsertStatement> statementsNearCaret() {
            List<InsertStatement> all;
            if (text.length() > InsertStatementParser.LARGE_DOCUMENT_THRESHOLD) {
                int from = Math.max(0, caret - WINDOW_RADIUS);
                while (from > 0 && text.charAt(from - 1) != '\n') {
                    from--;
                }
                int to = Math.min(text.length(), caret + WINDOW_RADIUS);
                all = InsertStatementParser.parseAll(SqlTokenizer.tokenize(text, from, to), text);
            } else {
                all = InsertStatementParser.parseAll(text);
            }
            if (all.size() <= MAX_STATEMENTS) {
                return all;
            }
            return all.stream()
                    .sorted(Comparator.comparingInt(s -> Math.abs(s.range().start() - caret)))
                    .limit(MAX_STATEMENTS)
                    .toList();
        }
    }
}
