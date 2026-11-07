package com.sqlassist.dbeaver.ui.hints;

import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineContentCodeMining;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.custom.StyledText;

/** Faint inline label drawn before a VALUES item; it is not part of the document. */
final class ColumnHintMining extends LineContentCodeMining {

    ColumnHintMining(Position position, String label, ICodeMiningProvider provider) {
        super(position, provider);
        setLabel(label);
    }

    @Override
    public Point draw(GC gc, StyledText textWidget, Color color, int x, int y) {
        return super.draw(gc, textWidget, dim(textWidget), x, y);
    }

    /** Halfway between text and background color, so it reads as ghost text in light and dark themes. */
    private static Color dim(StyledText widget) {
        RGB fg = widget.getForeground().getRGB();
        RGB bg = widget.getBackground().getRGB();
        return new Color((fg.red + bg.red) / 2, (fg.green + bg.green) / 2, (fg.blue + bg.blue) / 2);
    }
}
