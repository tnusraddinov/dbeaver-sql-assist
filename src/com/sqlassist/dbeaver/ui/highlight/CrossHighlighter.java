package com.sqlassist.dbeaver.ui.highlight;

import com.sqlassist.dbeaver.parser.CrossReference;
import com.sqlassist.dbeaver.parser.Span;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.viewers.IPostSelectionProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.custom.StyledText;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Module 1: while the caret is on an INSERT column name, highlights the matching
 * value in every VALUES tuple; while it is on a value, highlights the column.
 * One instance per SQL editor text widget; disposed together with the widget.
 */
public final class CrossHighlighter implements ISelectionChangedListener, ITextListener {

    public static final String ANNOTATION_TYPE = "com.sqlassist.dbeaver.crossHighlight";
    private static final String WIDGET_KEY = CrossHighlighter.class.getName();
    private static final int TEXT_CHANGE_DELAY_MS = 250;

    private final SQLEditorBase editor;
    private final TextViewer viewer;
    private final InsertModelCache cache = new InsertModelCache();
    private final Runnable deferredUpdate = this::update;
    private Annotation[] current = new Annotation[0];
    private boolean disposed;

    private CrossHighlighter(SQLEditorBase editor, TextViewer viewer) {
        this.editor = editor;
        this.viewer = viewer;
    }

    /** Idempotent: attaching twice to the same editor is a no-op. */
    public static void install(SQLEditorBase editor) {
        TextViewer viewer = editor.getTextViewer();
        if (viewer == null) {
            return;
        }
        StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed() || widget.getData(WIDGET_KEY) != null) {
            return;
        }
        CrossHighlighter highlighter = new CrossHighlighter(editor, viewer);
        widget.setData(WIDGET_KEY, highlighter);
        widget.addDisposeListener(e -> highlighter.dispose());
        ((IPostSelectionProvider) viewer).addPostSelectionChangedListener(highlighter);
        viewer.addTextListener(highlighter);
    }

    // Post-selection events are already debounced by JFace (fires after the caret settles).
    @Override
    public void selectionChanged(SelectionChangedEvent event) {
        update();
    }

    // Typing inside a tuple shifts indices; re-evaluate shortly after the edit.
    @Override
    public void textChanged(TextEvent event) {
        StyledText widget = viewer.getTextWidget();
        if (widget != null && !widget.isDisposed()) {
            widget.getDisplay().timerExec(TEXT_CHANGE_DELAY_MS, deferredUpdate);
        }
    }

    private void update() {
        if (disposed) {
            return;
        }
        IDocument document = viewer.getDocument();
        if (document == null) {
            clear();
            return;
        }
        int caret = viewer.getSelectedRange().x;
        Optional<CrossReference> ref;
        try {
            ref = cache.findAt(document, caret).flatMap(stmt -> stmt.crossReferenceAt(caret));
        } catch (RuntimeException e) {
            // The parser must never break the editor; just drop the highlight.
            ref = Optional.empty();
        }
        if (ref.isEmpty() || ref.get().targets().isEmpty()) {
            clear();
            return;
        }
        show(document, ref.get());
    }

    private void show(IDocument document, CrossReference ref) {
        Map<Annotation, Position> added = new HashMap<>();
        String sourceText = text(document, ref.source());
        String label = ref.kind() == CrossReference.Kind.COLUMN
                ? "Value for column #" + (ref.columnIndex() + 1) + " (" + sourceText + ")"
                : "Column #" + (ref.columnIndex() + 1) + " for value " + abbreviate(sourceText);
        for (Span target : ref.targets()) {
            if (target.length() > 0) {
                added.put(new Annotation(ANNOTATION_TYPE, false, label), new Position(target.start(), target.length()));
            }
        }
        if (ref.source().length() > 0) {
            added.put(new Annotation(ANNOTATION_TYPE, false, "Selected " + ref.kind().name().toLowerCase()),
                    new Position(ref.source().start(), ref.source().length()));
        }
        replace(added);
    }

    private void clear() {
        if (current.length > 0) {
            replace(Map.of());
        }
    }

    private void replace(Map<Annotation, Position> added) {
        IAnnotationModel model = editor.getAnnotationModel();
        if (model == null) {
            return;
        }
        Annotation[] next = added.keySet().toArray(new Annotation[0]);
        if (model instanceof IAnnotationModelExtension ext) {
            ext.replaceAnnotations(current, added);
        } else {
            for (Annotation a : current) {
                model.removeAnnotation(a);
            }
            added.forEach(model::addAnnotation);
        }
        current = next;
    }

    private void dispose() {
        if (disposed) {
            return;
        }
        clear();
        disposed = true;
        ((IPostSelectionProvider) viewer).removePostSelectionChangedListener(this);
        viewer.removeTextListener(this);
        cache.invalidate();
    }

    private static String text(IDocument document, Span span) {
        try {
            return document.get(span.start(), span.length());
        } catch (Exception e) {
            return "";
        }
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() > 40 ? oneLine.substring(0, 37) + "..." : oneLine;
    }
}
