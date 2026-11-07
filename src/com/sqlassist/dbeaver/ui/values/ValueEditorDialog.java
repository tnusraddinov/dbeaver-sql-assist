package com.sqlassist.dbeaver.ui.values;

import com.sqlassist.dbeaver.values.FormatException;
import com.sqlassist.dbeaver.values.JsonFormatter;
import com.sqlassist.dbeaver.values.ValueKind;
import com.sqlassist.dbeaver.values.XmlFormatter;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.FrameworkUtil;

/**
 * Module 5: edits the content of one SQL string value (JSON, XML or text) without
 * SQL quoting. JSON/XML open beautified; a single-line JSON value is written back
 * compact unless the user unticks "Write back as a single line".
 */
public final class ValueEditorDialog extends Dialog {

    private static final int VALIDATE_DELAY_MS = 300;

    private final String title;
    private final String original;
    private final boolean editable;
    private ValueKind kind;
    private String result;

    private StyledText text;
    private Combo kindCombo;
    private Button formatButton;
    private Button minifyButton;
    private Button singleLine;
    private Label status;
    private final Runnable validator = this::validate;

    public ValueEditorDialog(Shell parent, String title, String content, ValueKind kind, boolean editable) {
        super(parent);
        this.title = title;
        this.original = content;
        this.kind = kind;
        this.editable = editable;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    /** The edited content (unquoted), or {@code null} if cancelled. */
    public String result() {
        return result;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(title);
    }

    @Override
    protected Point getInitialSize() {
        return new Point(720, 560);
    }

    @Override
    protected IDialogSettings getDialogBoundsSettings() {
        IDialogSettings root = PlatformUI.getDialogSettingsProvider(FrameworkUtil.getBundle(getClass()))
                .getDialogSettings();
        IDialogSettings section = root.getSection("ValueEditorDialog");
        return section != null ? section : root.addNewSection("ValueEditorDialog");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        GridLayoutFactory.swtDefaults().numColumns(6).applyTo(area);

        new Label(area, SWT.NONE).setText("Content:");
        kindCombo = new Combo(area, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (ValueKind k : ValueKind.values()) {
            kindCombo.add(label(k));
        }
        kindCombo.select(kind.ordinal());
        kindCombo.addListener(SWT.Selection, e -> {
            kind = ValueKind.values()[kindCombo.getSelectionIndex()];
            updateButtons();
            validate();
        });

        formatButton = new Button(area, SWT.PUSH);
        formatButton.setText("Format");
        formatButton.addListener(SWT.Selection, e -> reformat(true));
        minifyButton = new Button(area, SWT.PUSH);
        minifyButton.setText("Minify");
        minifyButton.addListener(SWT.Selection, e -> reformat(false));

        Button wrap = new Button(area, SWT.CHECK);
        wrap.setText("Wrap lines");
        wrap.addListener(SWT.Selection, e -> text.setWordWrap(wrap.getSelection()));

        singleLine = new Button(area, SWT.CHECK);
        singleLine.setText("Write back as a single line");
        singleLine.setToolTipText("Minify JSON when applying, so the INSERT keeps one value per line");
        singleLine.setSelection(original.indexOf('\n') < 0);
        GridDataFactory.fillDefaults().grab(true, false).align(SWT.END, SWT.CENTER).applyTo(singleLine);

        text = new StyledText(area, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        text.setFont(JFaceResources.getTextFont());
        text.setEditable(editable);
        text.setText(initialText());
        text.addListener(SWT.KeyDown, e -> {
            if (e.character == 'a' && (e.stateMask & SWT.MOD1) != 0) {
                text.selectAll();
                e.doit = false;
            }
        });
        text.addModifyListener(e -> text.getDisplay().timerExec(VALIDATE_DELAY_MS, validator));
        GridDataFactory.fillDefaults().grab(true, true).span(6, 1).hint(SWT.DEFAULT, 300).applyTo(text);

        status = new Label(area, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).span(6, 1).applyTo(status);

        updateButtons();
        validate();
        text.setFocus();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        if (editable) {
            createButton(parent, IDialogConstants.OK_ID, "Apply to Script", true);
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        } else {
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CLOSE_LABEL, true);
        }
    }

    @Override
    protected void okPressed() {
        String value = text.getText();
        if (value.equals(initialText())) {
            result = original; // only reformatted for display: keep the script untouched
            super.okPressed();
            return;
        }
        if (kind == ValueKind.JSON && singleLine.getSelection()) {
            try {
                value = JsonFormatter.compact(value);
            } catch (FormatException e) {
                if (!MessageDialog.openQuestion(getShell(), title,
                        "The JSON is not valid, so it cannot be written as a single line.\n\n"
                                + e.describe(value) + "\n\nApply it exactly as typed?")) {
                    return;
                }
            }
        } else if (kind == ValueKind.XML && singleLine.getSelection()) {
            value = value.replaceAll(">\\s*\\n\\s*<", "><"); // drop indentation between tags only
        }
        result = value;
        super.okPressed();
    }

    // ------------------------------------------------------------------ helpers

    private String initialText() {
        try {
            return switch (kind) {
                case JSON -> JsonFormatter.pretty(original);
                case XML -> XmlFormatter.pretty(original);
                case TEXT -> original;
            };
        } catch (FormatException e) {
            return original; // show broken content unchanged; the status line explains
        }
    }

    private void reformat(boolean pretty) {
        String current = text.getText();
        try {
            String formatted = switch (kind) {
                case JSON -> pretty ? JsonFormatter.pretty(current) : JsonFormatter.compact(current);
                case XML -> XmlFormatter.pretty(current);
                case TEXT -> current;
            };
            if (!formatted.equals(current)) {
                text.setText(formatted);
            }
        } catch (FormatException e) {
            showError(e, current, true); // user asked to format: jump to the problem
        }
    }

    private void validate() {
        if (status == null || status.isDisposed()) {
            return;
        }
        String current = text.getText();
        try {
            switch (kind) {
                case JSON -> JsonFormatter.validate(current);
                case XML -> XmlFormatter.pretty(current);
                case TEXT -> {
                }
            }
            int lines = text.getLineCount();
            status.setForeground(null);
            status.setText((kind == ValueKind.TEXT ? "" : "Valid " + label(kind) + ". ")
                    + current.length() + " characters, " + lines + (lines == 1 ? " line." : " lines."));
        } catch (FormatException e) {
            showError(e, current, false); // live check while typing: never move the caret
        }
        status.getParent().layout(new Control[] {status});
    }

    private void showError(FormatException e, String current, boolean moveCaret) {
        status.setForeground(status.getDisplay().getSystemColor(SWT.COLOR_RED));
        status.setText(e.describe(current));
        if (moveCaret && e.offset() >= 0 && e.offset() <= current.length()) {
            text.setCaretOffset(e.offset());
            text.showSelection();
        }
    }

    private void updateButtons() {
        formatButton.setEnabled(editable && kind != ValueKind.TEXT);
        minifyButton.setEnabled(editable && kind == ValueKind.JSON);
        singleLine.setEnabled(editable && kind != ValueKind.TEXT);
    }

    private static String label(ValueKind k) {
        return switch (k) {
            case JSON -> "JSON";
            case XML -> "XML";
            case TEXT -> "Text";
        };
    }
}
