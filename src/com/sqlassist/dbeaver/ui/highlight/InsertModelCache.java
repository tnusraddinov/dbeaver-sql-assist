package com.sqlassist.dbeaver.ui.highlight;

import com.sqlassist.dbeaver.parser.InsertStatement;
import com.sqlassist.dbeaver.parser.InsertStatementParser;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;

import java.util.List;
import java.util.Optional;

/**
 * Parses the document once per modification stamp, so caret moves without
 * edits only cost a binary search. Very large documents fall back to a
 * windowed parse around the caret on every lookup.
 */
final class InsertModelCache {

    private long stamp = IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    private List<InsertStatement> statements = List.of();

    Optional<InsertStatement> findAt(IDocument document, int offset) {
        String text = document.get();
        if (text.length() > InsertStatementParser.LARGE_DOCUMENT_THRESHOLD) {
            invalidate();
            return InsertStatementParser.findAt(text, offset);
        }
        long current = document instanceof IDocumentExtension4 ext
                ? ext.getModificationStamp()
                : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
        if (current == IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP || current != stamp) {
            statements = InsertStatementParser.parseAll(text);
            stamp = current;
        }
        return InsertStatementParser.findAt(statements, offset);
    }

    void invalidate() {
        stamp = IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
        statements = List.of();
    }
}
