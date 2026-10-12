package com.origin.client.client;

// Persisted feature-mod toggle state, plus the runtime-only toggle-sneak
// state that doesn't belong on disk.
public final class OriginFeatures {
	public boolean zoomEnabled = true;
	public boolean freelookEnabled = true;
	public boolean hudInfoEnabled = true;
	public boolean toggleSprintEnabled = false;
	public boolean toggleSneakEnabled = false;
	public boolean fullbrightEnabled = false;
	public double zoomFov = 30.0;

	// Sprint keeps its memory: the toggle survives screens, server hops and a
	// full restart, so only an explicit un-toggle ever turns it off. Sneak
	// deliberately does NOT persist -- coming back crouched is worse than
	// pressing the key again.
	public boolean sprintToggledOn = false;
	public transient boolean sneakToggledOn = false;
}
