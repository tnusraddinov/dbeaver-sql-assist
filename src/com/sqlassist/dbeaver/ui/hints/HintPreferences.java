package com.sqlassist.dbeaver.ui.hints;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

/** Module 4 on/off switches, persisted in the workspace preferences. */
public final class HintPreferences {

    private static final String NODE = "com.sqlassist.dbeaver";
    private static final String ENABLED = "columnHints.enabled";
    private static final String SHOW_TYPES = "columnHints.showTypes";

    private HintPreferences() {
    }

    private static IEclipsePreferences node() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    public static boolean isEnabled() {
        return node().getBoolean(ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        node().putBoolean(ENABLED, enabled);
    }

    public static boolean showTypes() {
        return node().getBoolean(SHOW_TYPES, true);
    }
}
