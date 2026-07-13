package com.origin.client.client;

// Persisted feature-mod toggle state, plus a couple of runtime-only fields
// that don't belong on disk (the current toggle-sprint/sneak edge state).
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
