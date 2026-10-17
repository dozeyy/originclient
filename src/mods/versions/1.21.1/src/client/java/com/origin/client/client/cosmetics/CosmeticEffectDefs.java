package com.origin.client.client.cosmetics;

import java.util.HashMap;
import java.util.Map;

/**
 * The in-game particle recipe of every cosmetic that gives off sparks: ONE
 * table, item id -> {@link Spec}. Nothing else in the effects system knows an
 * item by name, so changing how an item sparkles (or adding one) is an edit to
 * this file only.
 *
 * Every number is in the STORE'S OWN UNITS so it can be copied straight from
 * website/js/cosmetics.js (the `sparks(...)` calls in buildCape / the head
 * builders and the `sparks:` / `rise:` fields of ART):
 *  - lengths are skin pixels (1 px = 1/16 block, the website's model unit);
 *  - times are seconds (the website loops every LOOP = 2.4 s; its particles
 *    live one loop or half a loop = 1.2 .. 2.4 s);
 *  - `count` is how many particles the website keeps alive at once; the
 *    in-game spawn rate is derived from it (count / average life), so a
 *    pasted number gives the same density.
 *
 * Synced from the website art as of 2026-10-17. When the store art changes,
 * update the numbers here; {@link CosmeticEffects} needs no change.
 */
public final class CosmeticEffectDefs {
	private CosmeticEffectDefs() {
	}

	/** Where on the player the sparks are born. */
	public enum Anchor {
		/** across the outer face of the cape (follows the cape's physics) */
		CAPE,
		/** on a square ring round the head (a crown / halo band) */
		HEAD
	}

	/**
	 * @param colors      particle colours (0xRRGGBB), picked round-robin
	 * @param count       particles alive at once on one player (website `count`)
	 * @param lifeMinSec  shortest particle life
	 * @param lifeMaxSec  longest particle life
	 * @param sizePx      side of one particle square (website PointsMaterial size)
	 * @param driftUpPx   how far it rises over its life (negative = sinks)
	 * @param driftBackPx how far it drifts away from the player's back over its life
	 * @param wobblePx    sideways sway amplitude (one full sine over its life)
	 * @param widthPx     CAPE: half-width of the spawn strip across the cape.
	 *                    HEAD: half-side of the square spawn ring.
	 * @param fromPx      CAPE: nearest spawn distance below the shoulders.
	 *                    HEAD: lowest spawn height above the feet.
	 * @param toPx        CAPE: farthest spawn distance below the shoulders.
	 *                    HEAD: highest spawn height above the feet.
	 * @param standoffPx  CAPE: how far off the cloth the sparks start
	 * @param glow        strength of the soft halo behind each square (0 = none)
	 */
	public record Spec(Anchor anchor, int[] colors, int count, float lifeMinSec, float lifeMaxSec, float sizePx,
			float driftUpPx, float driftBackPx, float wobblePx,
			float widthPx, float fromPx, float toPx, float standoffPx, float glow) {

		public float avgLifeSec() {
			return (lifeMinSec + lifeMaxSec) * 0.5f;
		}

		/** Particles born per second to keep about `count` alive. */
		public float perSecond() {
			return count / Math.max(0.2f, avgLifeSec());
		}
	}

	private static final Map<String, Spec> SPECS = new HashMap<>();

	/** The recipe for an item id, or null if the item gives off nothing. */
	public static Spec get(String id) {
		return id == null ? null : SPECS.get(id);
	}

	/** website buildCape: 26 sparks lifting off the outside of the cape and drifting behind. */
	private static void cape(String id, boolean rise, int... colors) {
		SPECS.put(id, new Spec(Anchor.CAPE, colors, 26, 1.2f, 2.4f, 0.8f,
				rise ? 6f : 3f, 3.5f, 0.6f,
				4.5f, 2f, 16f, 1.2f, 0.28f));
	}

	static {
		// website ART[...].sparks / .rise
		cape("cape-orbit", false, 0x83E2B5, 0x4BC8AE, 0xFFFFFF);
		cape("cape-glitch", false, 0xFF2E55, 0x2EFFA8, 0x2E7BFF);
		cape("cape-inferno", true, 0xFFB23E, 0xFF6A2B, 0xFFF1A8);
		cape("cape-aurora", false, 0x3EE8B0, 0x9A6BFF);
		cape("cape-starfall", false, 0xFFFFFF, 0xBCD2FF);

		// website buildCrown: 10 twinkles round the gold band (y 31..36), rising 1.2 px.
		SPECS.put("crown-pixel", new Spec(Anchor.HEAD, new int[] {0xFFFFFF, 0xFFF1B8}, 10, 1.2f, 2.4f, 0.8f,
				1.2f, 0f, 0f,
				4.85f, 31f, 36f, 0f, 0.22f));
	}
}
