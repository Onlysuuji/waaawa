package org.example2.solips;

public final class ClientFeatureToggle {
    private static volatile boolean enabled = true;

    private ClientFeatureToggle() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean setEnabled(boolean value) {
        boolean changed = enabled != value;
        enabled = value;
        return changed;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }
}
