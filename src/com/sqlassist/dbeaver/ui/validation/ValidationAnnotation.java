package com.sqlassist.dbeaver.ui.validation;

import com.sqlassist.dbeaver.validation.Problem;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.ui.texteditor.spelling.SpellingAnnotation;
import org.eclipse.ui.texteditor.spelling.SpellingProblem;

/**
 * DBeaver's SQL editor hover only shows text for its own problem annotations and for
 * {@link SpellingAnnotation}s. Extending SpellingAnnotation (with our own annotation
 * type, so the spell checker never removes it) gives the warning a tooltip without
 * replacing DBeaver's hover.
 */
final class ValidationAnnotation extends SpellingAnnotation {

    static final String ERROR_TYPE = "com.sqlassist.dbeaver.validation.error";
    static final String WARNING_TYPE = "com.sqlassist.dbeaver.validation.warning";

    ValidationAnnotation(Problem problem) {
        super(new MessageProblem(problem));
        setType(problem.severity() == Problem.Severity.ERROR ? ERROR_TYPE : WARNING_TYPE);
        setText(problem.message());
        setQuickFixable(false);
    }

    private static final class MessageProblem extends SpellingProblem {
        private final Problem problem;

        MessageProblem(Problem problem) {
            this.problem = problem;
        }

        @Override
        public int getOffset() {
            return problem.span().start();
        }

        @Override
        public int getLength() {
            return problem.span().length();
        }

        @Override
        public String getMessage() {
            return problem.message();
        }

        @Override
        public ICompletionProposal[] getProposals() {
            return new ICompletionProposal[0];
        }
    }
}
