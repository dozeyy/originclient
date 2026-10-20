package com.origin.client.client.hooks;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.OriginFreelookState;
import com.origin.client.client.OriginKeyBindings;
import com.origin.client.client.mods.BlockOverlayRenderer;
import com.origin.client.client.mods.ChunkBorderRenderer;
import com.origin.client.client.mods.GlintColorizer;
import com.origin.client.client.mods.HitColor;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.MotionBlur;
import com.origin.client.client.mods.Snaplook;
import com.origin.client.client.render.ColorGrade;
import com.origin.client.client.shaders.IrisBridge;
import com.origin.client.client.shaders.ShaderCompat;
import com.origin.client.client.util.Reflect;
import com.origin.client.client.waypoints.WaypointRenderer;
import com.origin.client.client.worldedit.WorldEditCui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The world area's Forge-event hooks (registered on both buses by OriginHooks)
 * plus the small per-frame state the world mixins share. 1.8.9's stand-in for
 * the world half of 1.16.5's OriginClientMod (the WorldRenderEvents.LAST pass,
 * the BLOCK_OUTLINE callback, Motion Blur / Snaplook ticks, WorldEditCui.init)
 * and of its GameRendererMixin (the once-a-frame zoom ease).
 *
 *   RenderTickEvent START  partial ticks, zoom ease, Hit Color / Glint upkeep
 *   ClientTickEvent END    Motion Blur, Snaplook, WorldEdit CUI handshake,
 *                          OptiFine zoom-key guard
 *   RenderWorldLastEvent   chunk borders, waypoint beams (+ the HUD projection
 *                          capture), WorldEdit selection -- GL model-view = camera,
 *                          coords relative to RenderManager.viewerPos
 *   DrawBlockHighlightEvent  Origin's block outline / overlay (cancels vanilla's)
 *   RenderGameOverlayEvent.Pre(ALL)  Color Saturation grade, before any HUD
 *   MouseEvent             scroll-to-zoom, Disable Hotbar Scrolling
 *   RenderLivingEvent.Specials.Pre  nametag hide toggles (incl. the sneaking path
 *                          vanilla draws without renderLivingLabel)
 *
 * Every handler is fail-soft: a throw costs that feature this frame, never the game.
 */
public final class WorldHooks {

	public WorldHooks() {
		// WorldEdit CUI's FML channel must exist before the first connection.
		try {
			WorldEditCui.init();
		} catch (Throwable t) {
			// it stays off; init logs its own reason
		}
	}

	// ---- per-frame state shared with the world mixins ----

	private static volatile float partialTicks = 1f;

	/** This frame's partial tick (RenderTickEvent START); 1 before the first frame. */
	public static float partialTicks() {
		return partialTicks;
	}

	// Eased zoom progress (0 = off, 1 = fully zoomed) + last-frame timestamp.
	// Advanced ONCE per frame (RenderTickEvent START), only READ by the FOV hook --
	// getFOVModifier runs several times a frame (world, hand, OptiFine's passes),
	// and advancing it there made the passes disagree mid-ease (1.16.5 finding).
	private static double zoomAnim = 0;
	private static long lastZoomNanos = 0;
	// How far zoom pulled the world FOV in on the previous world pass (<= 0).
	private static double lastZoomDelta = 0;

	private static void advanceZoom() {
		boolean active = Mods.on("zoom") && OriginClientMod.zoomActive;
		double target = active ? 1.0 : 0.0;
		long now = System.nanoTime();
		double dt = lastZoomNanos == 0 ? 16.7 : Math.min(64.0, (now - lastZoomNanos) / 1_000_000.0);
		lastZoomNanos = now;
		if (Mods.on("zoom") && Mods.bool("zoom", "smoothZoom")) {
			double rate = 1.0 - Math.exp(-dt / 70.0);   // ~70ms time constant, per-frame
			zoomAnim += (target - zoomAnim) * rate;
			if (Math.abs(target - zoomAnim) < 0.0015) {
				zoomAnim = target;
			}
		} else {
			zoomAnim = target;
		}
	}

	/**
	 * ZoomFovMixin (EntityRenderer.getFOVModifier RETURN): the zoomed FOV for a
	 * world pass. `useFovSetting` false is the first-person HAND pass -- zoom never
	 * touches it (the held item stays its normal size, like OptiFine/Lunar zoom).
	 */
	public static float zoomFov(float vanilla, boolean useFovSetting) {
		if (!useFovSetting) {
			return vanilla;
		}
		double delta = 0;
		if (zoomAnim > 0.001) {
			double tfov = Mods.num("zoom", "fov") * OriginClientMod.zoomScrollFactor;
			tfov = Math.max(1.0, Math.min(vanilla, tfov));   // zoom only narrows the FOV
			delta = (tfov - vanilla) * zoomAnim;
		}
		// 1.8.9 (same as 1.16.5): terrain is culled with the frame's FULL projection,
		// zoom included, and RenderGlobal only re-walks its visible chunks when the
		// camera moves or turns -- zoom in, hold still, zoom out, and every chunk
		// outside the zoomed cone stays undrawn. Whenever the zoom FOV WIDENS, ask
		// for a re-walk the way vanilla's F5 key does (setDisplayListEntitiesDirty).
		if (delta > lastZoomDelta + 1.0e-6) {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.renderGlobal != null) {
				mc.renderGlobal.setDisplayListEntitiesDirty();
			}
		}
		lastZoomDelta = delta;
		return (float) (vanilla + delta);
	}

	// ---- OptiFine ----

	private static Method ofIsShaders, ofIsFastRender;
	private static boolean ofLooked = false;

	private static void lookUpOptiFine() {
		if (ofLooked) {
			return;
		}
		ofLooked = true;
		if (!com.origin.client.OriginClient.isOptiFineLoaded()) {
			return;
		}
		Class<?> config = Reflect.type("Config", "net.optifine.Config");
		if (config != null) {
			ofIsShaders = Reflect.method(config, new Class<?>[0], "isShaders");
			ofIsFastRender = Reflect.method(config, new Class<?>[0], "isFastRender");
		}
	}

	/** True while an OptiFine shader pack is active (Config.isShaders()); false without OptiFine. */
	public static boolean optifineShadersActive() {
		lookUpOptiFine();
		return ofIsShaders != null && Boolean.TRUE.equals(Reflect.call(ofIsShaders, null, Boolean.FALSE));
	}

	/**
	 * Whether a vanilla post chain (ShaderGroup) can run this frame: framebuffers on
	 * (the FBO option / driver support), no OptiFine shader pack (it owns the
	 * pipeline) and no OptiFine Fast Render (which skips the framebuffer the chain
	 * reads). Motion Blur and Color Saturation step aside otherwise.
	 */
	public static boolean postChainsUsable() {
		if (!OpenGlHelper.isFramebufferEnabled() || optifineShadersActive()) {
			return false;
		}
		return ofIsFastRender == null || !Boolean.TRUE.equals(Reflect.call(ofIsFastRender, null, Boolean.FALSE));
	}

	// OptiFine's own zoom key (GameSettings.ofKeyBindZoom, default C) zooms inside
	// getFOVModifier and switches on smooth camera. With Origin's zoom enabled on
	// the same key both would fire, so while that is the case OptiFine's binding is
	// parked on "none" (0) -- and put back the moment Origin's zoom is off or moves
	// to another key. GameSettingsZoomMixin swaps the real key back in around every
	// options save, so options.txt always keeps the player's own OptiFine binding.
	private static Field ofZoomField;
	private static boolean ofZoomLooked = false;
	private static int parkedOfZoomKey = 0;

	private static KeyBinding ofZoomKey(GameSettings gs) {
		if (!ofZoomLooked) {
			ofZoomLooked = true;
			if (com.origin.client.OriginClient.isOptiFineLoaded()) {
				ofZoomField = Reflect.field(GameSettings.class, "ofKeyBindZoom");   // OptiFine's own (unobfuscated) field
			}
		}
		Object kb = ofZoomField == null ? null : Reflect.get(ofZoomField, gs, (Object) null);
		return kb instanceof KeyBinding ? (KeyBinding) kb : null;
	}

	private static void guardOptiFineZoom(Minecraft mc) {
		KeyBinding of = ofZoomKey(mc.gameSettings);
		if (of == null) {
			return;
		}
		int ours = Mods.keyCode("zoom", "key");
		int ourBind = OriginKeyBindings.zoom != null ? OriginKeyBindings.zoom.getKeyCode() : 0;
		if (parkedOfZoomKey == 0) {
			int code = of.getKeyCode();
			if (Mods.on("zoom") && code != 0 && (code == ours || code == ourBind)) {
				parkedOfZoomKey = code;
				of.setKeyCode(0);
				KeyBinding.resetKeyBindingArrayAndHash();
			}
		} else {
			boolean stillClash = Mods.on("zoom") && (parkedOfZoomKey == ours || parkedOfZoomKey == ourBind);
			if (!stillClash || of.getKeyCode() != 0) {
				// mod off / key moved: give OptiFine its key back (unless the player
				// rebound OptiFine's zoom meanwhile -- then theirs stands)
				if (of.getKeyCode() == 0) {
					of.setKeyCode(parkedOfZoomKey);
					KeyBinding.resetKeyBindingArrayAndHash();
				}
				parkedOfZoomKey = 0;
			}
		}
	}

	/** GameSettingsZoomMixin, saveOptions HEAD: the player's real OptiFine zoom key goes into the file. */
	public static void beforeOptionsSave(GameSettings gs) {
		KeyBinding of = parkedOfZoomKey == 0 ? null : ofZoomKey(gs);
		if (of != null && of.getKeyCode() == 0) {
			of.setKeyCode(parkedOfZoomKey);
		}
	}

	/** GameSettingsZoomMixin, saveOptions RETURN: park it again while the clash lasts. */
	public static void afterOptionsSave(GameSettings gs) {
		KeyBinding of = parkedOfZoomKey == 0 ? null : ofZoomKey(gs);
		if (of != null && of.getKeyCode() == parkedOfZoomKey) {
			of.setKeyCode(0);
		}
	}

	// ---- freelook camera swap (FreelookCameraMixin / ActiveRenderInfoMixin) ----

	private static Entity swapped;
	private static float sYaw, sPitch, sPrevYaw, sPrevPitch;

	/**
	 * Freelook: while the camera is being oriented, the player's rotation reads as
	 * the freelook camera's, so vanilla's own camera code (including the F5 orbit
	 * distance raytrace) builds the freelook view; restored by {@link #endCameraSwap}.
	 * Only the camera sees it -- the body, head, hitscan and the server never do.
	 */
	public static void beginCameraSwap(Entity e) {
		if (swapped != null || e == null || !OriginFreelookState.active) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (e != mc.thePlayer) {
			return;
		}
		swapped = e;
		sYaw = e.rotationYaw;
		sPitch = e.rotationPitch;
		sPrevYaw = e.prevRotationYaw;
		sPrevPitch = e.prevRotationPitch;
		e.rotationYaw = e.prevRotationYaw = OriginFreelookState.cameraYaw;
		e.rotationPitch = e.prevRotationPitch = OriginFreelookState.cameraPitch;
	}

	public static void endCameraSwap() {
		Entity e = swapped;
		if (e == null) {
			return;
		}
		swapped = null;
		e.rotationYaw = sYaw;
		e.rotationPitch = sPitch;
		e.prevRotationYaw = sPrevYaw;
		e.prevRotationPitch = sPrevPitch;
	}

	/**
	 * FreelookTurnMixin (Entity.setAngles HEAD): while freelook is held, the mouse
	 * turns the camera accumulator instead of the player. setAngles gets vanilla's
	 * already-sensitivity-scaled deltas; 0.15 is its own degrees-per-unit factor.
	 * Returns true when the turn was consumed (cancel vanilla's).
	 */
	public static boolean freelookTurn(Entity e, float yaw, float pitch) {
		if (!OriginFreelookState.active || e != Minecraft.getMinecraft().thePlayer) {
			return false;
		}
		double yawSign = Mods.bool("freelook", "invertYaw") ? -1 : 1;
		double pitchSign = Mods.bool("freelook", "invertPitch") ? -1 : 1;
		// Yaw is unlimited (full 360° orbit); pitch clamps at ±90 like F5. setAngles
		// SUBTRACTS pitch (mouse up = look up), and vanilla already folded invert-mouse in.
		OriginFreelookState.cameraYaw += (float) (yaw * 0.15 * yawSign);
		OriginFreelookState.cameraPitch = (float) Math.max(-90.0, Math.min(90.0,
				OriginFreelookState.cameraPitch - pitch * 0.15 * pitchSign));
		return true;
	}

	// ---- Full Bright (FullbrightMixin, EntityRenderer.updateLightmap HEAD/RETURN) ----

	private static float savedGamma;
	private static boolean gammaSwapped = false;

	/**
	 * 1.8.9 does NOT clamp gammaSetting inside updateLightmap, so a high gamma just
	 * works there. Instead of writing the option (it would end up in options.txt),
	 * the lightmap update alone sees the boosted value: swapped in at HEAD, the
	 * player's own value back at RETURN. Full Bright = 15 (flat maximum); otherwise
	 * Boost Factor (1..10, 1 = vanilla).
	 */
	public static void beginLightmap(GameSettings gs) {
		if (gammaSwapped || gs == null || !Mods.on("fullbright")) {
			return;
		}
		float want;
		if (Mods.bool("fullbright", "fullBright")) {
			want = 15f;
		} else {
			double boost = Mods.num("fullbright", "gamma");
			if (boost <= 1.0) {
				return;
			}
			want = (float) boost;
		}
		savedGamma = gs.gammaSetting;
		gs.gammaSetting = want;
		gammaSwapped = true;
	}

	public static void endLightmap(GameSettings gs) {
		if (gammaSwapped && gs != null) {
			gs.gammaSetting = savedGamma;
			gammaSwapped = false;
		}
	}

	// ---- events ----

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		if (event.phase != TickEvent.Phase.START) {
			return;
		}
		partialTicks = event.renderTickTime;
		try {
			advanceZoom();
		} catch (Throwable ignored) {
			// zoom holds its last ease this frame
		}
		try {
			HitColor.sync();
			GlintColorizer.sync();
		} catch (Throwable ignored) {
			// colours stay as they were
		}
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		try {
			MotionBlur.tick(mc);
		} catch (Throwable ignored) {
			// blur keeps its last state
		}
		// Snaplook ticks with or without a player, so leaving a world mid-look
		// still restores the saved camera view.
		try {
			Snaplook.tick(mc);
		} catch (Throwable ignored) {
			// key just doesn't fire this tick
		}
		try {
			WorldEditCui.tick(mc);
		} catch (Throwable ignored) {
			// handshake retried next tick
		}
		try {
			if (mc.gameSettings != null) {
				guardOptiFineZoom(mc);
			}
		} catch (Throwable ignored) {
			// OptiFine's binding is left as it is
		}
		try {
			warnIncompatiblePack(mc);
		} catch (Throwable ignored) {
			// no warning is better than a broken tick
		}
	}

	private static boolean packChecked;

	/**
	 * Once per session, on the first tick with a player: if the shader pack OptiFine
	 * has selected is built for newer Minecraft, say so in chat (one line). The zip
	 * is inspected on a daemon thread (ShaderCompat), the player's files are never
	 * touched, and the pack still loads exactly as the player chose.
	 */
	private static void warnIncompatiblePack(Minecraft mc) {
		if (mc.thePlayer == null) {
			return;
		}
		if (!packChecked) {
			packChecked = true;
			String sel = IrisBridge.selectedPack();
			if (sel != null) {
				ShaderCompat.startSelectedCheck(sel, IrisBridge.shaderpacksDir().toFile());
			}
			return;
		}
		String w = ShaderCompat.takeWarning();
		if (w != null) {
			mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
					"§e[Origin] " + w + " Pick another in Video Settings > Shaders."));
		}
	}

	/**
	 * The world overlays, in 1.16.5's order. RenderWorldLastEvent fires at the end
	 * of EntityRenderer.renderWorldPass with the camera transform still on the GL
	 * model-view stack and the world's depth buffer intact; every renderer emits
	 * camera-relative vertices (world minus RenderManager.viewerPos) and flushes
	 * its own buffer before returning. (Friends & Party's world pass is
	 * SocialHooks' own RenderWorldLastEvent handler.)
	 */
	@SubscribeEvent
	public void onRenderWorldLast(RenderWorldLastEvent event) {
		float pt = event.partialTicks;
		try {
			ChunkBorderRenderer.render(pt);
		} catch (Throwable t) {
			// overlay must never take the frame down
		}
		try {
			WaypointRenderer.render(pt);
		} catch (Throwable t) {
			// waypoints must never take the frame down
		}
		try {
			WorldEditCui.renderWorld(pt);
		} catch (Throwable t) {
			// the selection just doesn't draw this frame
		}
	}

	@SubscribeEvent
	public void onBlockHighlight(DrawBlockHighlightEvent event) {
		try {
			if (!BlockOverlayRenderer.onBlockOutline(event.player, event.target, event.subID, event.partialTicks)) {
				event.setCanceled(true);
			}
		} catch (Throwable t) {
			// fail-soft: let vanilla draw its outline
		}
	}

	// HIGHEST: the grade must run before any HUD draws into the frame.
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
		if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
			return;
		}
		try {
			ColorGrade.process(event.partialTicks);
		} catch (Throwable ignored) {
			// ColorGrade latches itself off on error
		}
	}

	/**
	 * Scroll to Zoom: while actively zoomed, the wheel changes zoom depth (a
	 * transient multiplier applied in zoomFov) instead of scrolling the hotbar.
	 * Disable Hotbar Scrolling (SETTINGS > General): swallow the wheel in-world so
	 * it never changes the held slot (screens and spectator mode are left alone).
	 * 1.8.9: Forge's MouseEvent is posted for every LWJGL mouse event the game
	 * loop handles in-world (no screen open), before the hotbar sees it.
	 */
	@SubscribeEvent
	public void onMouse(MouseEvent event) {
		if (event.dwheel == 0) {
			return;
		}
		try {
			if (Mods.on("zoom") && OriginClientMod.zoomActive && Mods.bool("zoom", "scrollZoom")) {
				double notches = Math.abs(event.dwheel) >= 120 ? event.dwheel / 120.0 : Math.signum(event.dwheel);
				double speed = Math.max(0.1, Mods.num("zoom", "scrollSpeed"));
				double f = OriginClientMod.zoomScrollFactor * Math.pow(1.15, -notches * speed);
				OriginClientMod.zoomScrollFactor = Math.max(0.2, Math.min(3.0, f));
				event.setCanceled(true);
				return;
			}
			Minecraft mc = Minecraft.getMinecraft();
			if (Mods.bool(Mods.GENERAL_ID, "disableHotbarScrolling")
					&& mc.currentScreen == null && mc.thePlayer != null && !mc.thePlayer.isSpectator()) {
				event.setCanceled(true);
			}
		} catch (Throwable ignored) {
			// the wheel behaves like vanilla
		}
	}

	/**
	 * Nametag hide toggles (Toggle All / Toggle Players / Hide in F1) for every
	 * living entity's name pass -- including the sneaking path, which vanilla
	 * draws itself instead of going through renderLivingLabel (NametagLabelMixin
	 * covers the rest). 1.8.9 already hides every tag under F1 (Minecraft.isGuiEnabled).
	 */
	@SubscribeEvent
	public void onLivingSpecials(RenderLivingEvent.Specials.Pre<?> event) {
		try {
			if (nametagHidden(event.entity)) {
				event.setCanceled(true);
			}
		} catch (Throwable ignored) {
			// the tag draws as vanilla
		}
	}

	/** The Nametags mod's hide toggles for one entity's tag. */
	public static boolean nametagHidden(Entity entity) {
		if (!Mods.on("nametags")) {
			return false;
		}
		Minecraft mc = Minecraft.getMinecraft();
		boolean f1 = mc.gameSettings.hideGUI && Mods.bool("nametags", "hideInF1");
		return OriginClientMod.nametagsHidden || (OriginClientMod.playerNametagsHidden && entity instanceof EntityPlayer) || f1;
	}
}
