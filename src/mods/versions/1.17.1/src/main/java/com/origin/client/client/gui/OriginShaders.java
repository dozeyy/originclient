package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * The scalable (SDF/MSDF) rendering backend for the Origin menus: MSDF text, the
 * icon-atlas SDF, the rounded-box SDF for panels/cards, and the fullscreen colour
 * grade (Color Saturation) — 1.21.1's four core shaders.
 *
 * PER-VERSION DELTA (1.17.1): Fabric API 0.46.1+1.17 has no
 * CoreShaderRegistrationCallback (that hook arrived later). Vanilla's own
 * ShaderInstance constructor loads + compiles a core shader directly (it is how
 * GameRenderer bootstraps its own), so each program is compiled the first time a
 * draw needs it, on the render thread where a GL context exists. 1.17's
 * ShaderInstance builds "shaders/core/" + name + ".json" with NO namespace support
 * (a "originclient:" prefix would land inside the namespace and throw), so these
 * four live under assets/minecraft/shaders/core with origin-unique names and
 * un-namespaced vertex/fragment references.
 *
 * Fail-soft (mandate 4): each program compiles independently; a failure leaves
 * just that field null and its callers take their software path (vanilla font,
 * linear icon mask, software AA corners, no grade). Never retried.
 *
 * State discipline: the immediate-mode draws these shaders back MUST call
 * {@link #restoreState()} afterwards, or the last GUI frame's blend/shader-color
 * leaks into world rendering (that was the broken-sky bug).
 */
public final class OriginShaders {
	public static ShaderInstance MSDF;
	/** Higher-range monochrome SDF used by the custom icon atlas. */
	public static ShaderInstance ICON;
	public static ShaderInstance ROUND;
	/** Fullscreen scene color-grade (Color Saturation), invoked by ColorGrade with
	 *  our own GL-state discipline instead of a vanilla PostChain. */
	public static ShaderInstance GRADE;

	private static boolean registered = false;
	private static boolean compiled = false;
	private static boolean warnedTextFallback = false;
	private static boolean warnedRoundFallback = false;

	private OriginShaders() {
	}

	/** Call once from client init. The real work is deferred to
	 *  {@link #ensureCompiled()} on the render thread — no GL context exists
	 *  yet at client-init time. */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		OriginClient.LOGGER.info("Origin: scalable core shaders (MSDF text + icon/rounded SDF + grade) compile on first draw.");
	}

	/** Compile all four programs the first time a draw path needs one. MUST run on the
	 *  render thread (every caller is a draw path). One attempt per session. */
	public static void ensureCompiled() {
		// Not while a resource reload is in flight (the loading overlay is up and the
		// pack list is being rebuilt): callers take their fallback for those frames.
		// A FINISHED reload is fine (the pack list is final): the startup settle hold
		// renders the title under the finished loading picture, and compiling there
		// means (a) ModIcons, which picks its atlas once, sees ICON and takes the SDF
		// atlas instead of latching the blurrier mask for the session, and (b) the
		// four compiles (138 ms on CI's llvmpipe) land in the unseen hold, not on the
		// dissolve's last frame, where the first overlay-free draw used to pay them
		// (frame log 2026-10-19: 101-138 ms, title render).
		if (compiled || reloadInFlight()) {
			return;
		}
		compiled = true;
		MSDF = compile("rendertype_origin_msdf", DefaultVertexFormat.POSITION_TEX_COLOR, "MSDF text");
		ICON = compile("rendertype_origin_icon", DefaultVertexFormat.POSITION_TEX_COLOR, "icon SDF");
		ROUND = compile("rendertype_origin_round", DefaultVertexFormat.POSITION_TEX, "rounded-box SDF");
		GRADE = compile("rendertype_origin_grade", DefaultVertexFormat.POSITION_TEX, "fullscreen color-grade");
	}

	private static boolean reloadInFlight() {
		Object overlay = Minecraft.getInstance().getOverlay();
		if (overlay == null) {
			return false;
		}
		if (overlay instanceof com.origin.client.client.mixin.loading.LoadingOverlayAccessor) {
			net.minecraft.server.packs.resources.ReloadInstance reload =
					((com.origin.client.client.mixin.loading.LoadingOverlayAccessor) overlay).originclient$reload();
			return reload == null || !reload.isDone();
		}
		return true;
	}

	private static ShaderInstance compile(String name, VertexFormat format, String what) {
		try {
			ShaderInstance shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(), name, format);
			OriginClient.LOGGER.info("Origin: {} shader compiled + loaded OK.", what);
			return shader;
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("Origin: {} shader failed to compile — using its software fallback.", what, t);
			return null;
		}
	}

	/** Vector text + curves are ALWAYS on now (Will): the user-facing toggle was
	 *  removed, so this is unconditionally true. The round/MSDF paths still fail
	 *  soft on their own if a shader doesn't compile (roundActive / ready()). */
	public static boolean enabled() {
		return true;
	}

	/** Rounded-box SDF path is usable. Restricted to screens (menus) so the
	 *  immediate-mode draws never touch the in-world HUD render path. */
	public static boolean roundActive() {
		if (Minecraft.getInstance().screen == null) {
			return false;
		}
		ensureCompiled();
		if (enabled() && ROUND == null && !warnedRoundFallback) {
			warnedRoundFallback = true;
			OriginClient.LOGGER.warn("Origin: SDF is ON but the rounded-box shader did NOT load — "
					+ "using the software rounded-rect fallback. Check the log above for a shader compile error.");
		}
		return enabled() && ROUND != null;
	}

	/** Called by OriginSdfFont when text falls back to vanilla, so a non-loading
	 *  shader is visible in the log instead of silently reverting. */
	public static void noteTextFallback() {
		if (!warnedTextFallback) {
			warnedTextFallback = true;
			OriginClient.LOGGER.warn("Origin: SDF is ON but the MSDF text shader/atlas did NOT load — "
					+ "using the vanilla-font fallback. Check the log above for a shader compile or resource error.");
		}
	}

	/**
	 * Restore GL state after an immediate-mode custom-shader draw. Without this,
	 * blend + shader colour left set by the GUI draw leak into the next draw and,
	 * once the screen closes, into world/sky rendering (the black-sky bug). The
	 * next GUI element and every world RenderType set their own state before
	 * drawing, so returning to the disabled-blend / neutral-colour default here is
	 * safe and isolates our draws.
	 */
	public static void restoreState() {
		RenderSystem.disableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
	}
}
