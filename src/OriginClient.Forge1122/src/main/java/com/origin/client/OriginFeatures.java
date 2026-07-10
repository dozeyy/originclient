package com.origin.client;

/**
 * Persisted feature-toggle state, shared in shape with the Fabric build so a
 * player's settings and feel carry across versions. Runtime-only edge fields
 * are marked transient so they never hit disk.
 */
public final class OriginFeatures {
    public boolean zoomEnabled = true;
    public boolean freelookEnabled = true;
    public boolean hudInfoEnabled = true;
    public boolean toggleSprintEnabled = false;
    public boolean toggleSneakEnabled = false;
    public boolean fullbrightEnabled = false;
    public double zoomFov = 30.0;

    public transient boolean sprintToggledOn = false;
    public transient boolean sneakToggledOn = false;
}
