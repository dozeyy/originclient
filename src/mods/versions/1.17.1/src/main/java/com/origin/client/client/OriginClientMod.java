package com.origin.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.gui.OriginModMenuScreen;
import com.origin.client.client.mods.ChunkBorderRenderer;
import com.origin.client.client.mods.MotionBlur;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.util.KeyCodes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

// Client entrypoint + per-tick feature hub. All feature state lives in the
// Mods registry (originclient-mods.json); the legacy FEATURES object stays
// only as the migration source and for its transient sprint/sneak state.
public class OriginClientMod implements ClientModInitializer {
	public static final OriginFeatures FEATURES = OriginConfig.load();

	private boolean freelookWasDown = false;
	/** True while sneakKeyDown() reads the real Sneak key through isDown() (mouse binds),
	 *  so keyHeldByToggle() can't answer for it and the toggle never feeds itself. */
	private static boolean readingRealSneakKey = false;
	private net.minecraft.client.CameraType savedPerspective = null;
	// static: OptionsSaveMixin reads them around every options.txt write (chatOptionsForSave)
	private static boolean chatApplied = false;
	private static double savedChatOpacity = 1.0, savedChatScale = 1.0, savedChatTextOpacity = 1.0;
	private boolean hitboxesApplied = false;
	private boolean chunkKeyWasDown = false;
	private boolean sprintKeyWasDown = false, sneakKeyWasDown = false;
	private boolean scoreboardKeyWasDown = false;
	private boolean fullbrightKeyWasDown = false;
	private boolean zoomKeyWasDown = false;
	private boolean timeIncWasDown = false, timeDecWasDown = false;
	// static so previewTimeSlider (called from the mod menu) can fold it in too.
	private static double timePassageAccum = 0;

	// Live time-changer output, read by LevelTimeMixin every frame.
	public static volatile double timeOverride = 6000;
	// Toggle-mode zoom latch. zoomActive = zoom is engaged this tick (the target
	// GameRendererMixin eases the FOV toward, frame-side). zoomScrollFactor is a
	// transient scroll-to-zoom multiplier, reset whenever zoom disengages.
	public static volatile boolean zoomToggled = false;
	public static volatile boolean zoomActive = false;
	public static volatile double zoomScrollFactor = 1.0;
	// Nametag toggle latches, read by EntityNametagMixin.
	public static volatile boolean nametagsHidden = false, playerNametagsHidden = false;
	// Whether the Tab Editor's custom player-list overlay should draw this frame. Set
	// each tick; read by GuiHudMixin. We drive our own visibility (not vanilla's,
	// which refuses to show the list on a solo local server) so it also previews in
	// single-player and never double-draws with vanilla (PlayerTabOverlayMixin cancels).
	public static volatile boolean tabListVisible = false;
	private boolean nametagAllKeyWasDown = false, nametagPlayerKeyWasDown = false;

	// Tab Editor: sticky tap-vs-hold state + two-way keybind sync bookkeeping.
	private boolean tabKeyWasDown = false;
	private boolean tabStickyOn = false;
	private long tabPressAt = 0;
	private boolean tabSyncInit = false;
	private int lastOurTabKey = -1, lastMcTabKey = -1;
	// Zoom / Freelook: the mod-menu key and its Controls entry are ONE key (syncedKey).
	private final KeyLink zoomLink = new KeyLink(), freelookLink = new KeyLink();
	// Zoomed Sensitivity + Smooth Camera (zoom/freelook) save-and-restore state.
	private static boolean zoomSensApplied = false;
	private static double savedSensitivity = 0.5;
	private static double zoomedSensitivity = 0.5;
	private static boolean smoothCamApplied = false;
	private static boolean savedSmoothCam = false;
	// Fly Boost restore latch + thunder-sound cadence counter.
	private boolean flyBoostApplied = false;
	// Fly Boost's own numbers: the speed in effect before boosting (what comes back when the
	// boost ends) and the boosted speed it last set (a different reading = someone else,
	// e.g. a server plugin, changed the speed meanwhile, and that becomes the new base).
	private float flyBoostBase, flyBoostSet;
	private int thunderSoundTicks = 0;
	// Edge-trigger for death waypoints: true while the player is alive, so a death
	// drops exactly one waypoint on the alive→dead transition.
	private boolean wasAlive = false;

	@Override
	public void onInitializeClient() {
		// Which of Origin's mods step aside for the player's own (freecam -> Freelook,
		// a player's JEI -> Origin's JEI switch, ...). First, before anything reads Mods.on.
		com.origin.client.client.mods.StepAsideDetect.run();
		OriginDevShots.applyStyleOverride();   // dev-only (env ORIGIN_SHOTS_STYLE); no-op for players
		OriginKeyBindings.register();
		// Held Backspace/arrows repeat in Origin's text fields (pre-1.19.3 gate; see the class).
		com.origin.client.client.gui.OriginKeyRepeat.register();
		// Register the scalable (MSDF text + rounded-box SDF) core shaders for the
		// menus. Fails soft — if they don't load, the UI uses the software path.
		com.origin.client.client.gui.OriginShaders.register();
		// Origin's own textures + metadata decode in the startup reload's prepare
		// stage, on its worker threads, instead of on the render thread at first draw.
		OriginAssets.register();
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		// Tells Origin Launcher the game is past its loading screen (OriginHandoff).
		ClientTickEvents.END_CLIENT_TICK.register(OriginHandoff::tick);
		// Vanilla options save the moment they change (Settings Sync needs it).
		OptionsAutosave.register();
		// Team View, Discord presence (always on) and Hypixel register their own events.
		com.origin.client.client.mods.ServerMods.register();
		// Saturation mod's food tooltip ("Hunger +3, Saturation +3.6").
		com.origin.client.client.mods.SaturationOverlay.registerTooltip();
		// Single authoritative autosave when the player leaves the game (quit to
		// desktop / window close). Mod + HUD settings already save eagerly on
		// every change; this flushes all three stores once on exit so nothing is
		// ever lost — including vanilla game options, which the mod otherwise
		// never writes. Per-version isolation is automatic: options.txt and the
		// config/ dir both live inside this version's own instance folder.
		ClientLifecycleEvents.CLIENT_STOPPING.register(this::onClientStopping);
		// Origin's HUD is drawn from GuiHudMixin (Gui.render RETURN, high order)
		// instead of HudRenderCallback so it always lands on top of any other
		// mod's HUD — see that mixin for why the callback can't guarantee this.

		// Inject a "Download Shaders" button into Iris's own shader menu so the
		// whole shader flow lives where Sodium/Iris users already look — no tab
		// in the mod menu. Detected by class name (no compile dep on Iris),
		// added via Fabric's screen API (no Iris mixin to break on updates).
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen.getClass().getName().contains("ShaderPackScreen")) {
				var buttons = net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen);
				// Iris re-inits its screen on resize and every time we return from
				// the browser — guard so our button is added exactly once and never
				// stacks. This is the ONLY shader-download entry point (Iris ships
				// no Modrinth/download button of its own).
				net.minecraft.network.chat.Component label =
						new net.minecraft.network.chat.TextComponent("Download Shaders");
				boolean present = buttons.stream().anyMatch(w ->
						w instanceof net.minecraft.client.gui.components.Button b
								&& label.equals(b.getMessage()));
				if (!present) {
					buttons.add(new com.origin.client.client.shaders.OriginShaderButton(6, 6, 120, 20, label,
							b -> client.setScreen(new com.origin.client.client.shaders.ShaderBrowserScreen(screen))));
				}
			}
		});
		// Resource Pack Hub: a "Browse Packs" button on Minecraft's own Resource
		// Packs screen (the data-pack screen is the same class, so match on its
		// title key -- a TranslatableComponent before 1.19). The hub applies its
		// staged edits to this screen's list once it has re-initialised on return
		// (onPackScreenInit). Uninstalls that had to wait for Minecraft to release a
		// pack finish at startup / after reloads.
		com.origin.client.client.packs.PackHub.register();
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen instanceof net.minecraft.client.gui.screens.packs.PackSelectionScreen
					&& screen.getTitle() instanceof net.minecraft.network.chat.TranslatableComponent tc
					&& "resourcePack.title".equals(tc.getKey())) {
				com.origin.client.client.packs.ResourcePackHubScreen.onPackScreenInit(screen);
				var buttons = net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen);
				net.minecraft.network.chat.Component label =
						new net.minecraft.network.chat.TextComponent("Browse Packs");
				boolean present = buttons.stream().anyMatch(w ->
						w instanceof net.minecraft.client.gui.components.Button b
								&& label.equals(b.getMessage()));
				if (!present) {
					buttons.add(new com.origin.client.client.shaders.OriginShaderButton(6, 6, 120, 20, label,
							b -> client.setScreen(new com.origin.client.client.packs.ResourcePackHubScreen(screen))));
				}
			}
		});
		// Every out-of-world menu gets its backdrop here (Minecraft's own screens:
		// the version's panorama, blurred and dimmed; Origin's own: the rings). It is
		// drawn in beforeRender -- once, before ANY screen content -- so it clears
		// the frame for EVERY screen, including list screens (SelectWorld,
		// multiplayer, ...) that never call renderBackground and would otherwise
		// show the previous screen bleeding through. The title screen paints its
		// own backdrop (TitleScreenMixin), so it's excluded to avoid a double pass.
		// In-world screens (level != null) keep vanilla's blurred-world backdrop.
		// (1.21.1 draws this from Screen.renderBackground instead; pre-1.20 that
		// method has no mouse args and list screens skip it, hence this hook.)
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
				return;
			}
			// Pre-1.20 Fabric screen events hand us the PoseStack (GuiGraphics is
			// 1.20+); wrap it in the module's Gfx shim at the boundary.
			net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.beforeRender(screen).register((s, poseStack, mx, my, tick) -> {
				try {
					Minecraft mc = Minecraft.getInstance();
					if (mc.level != null
							|| !com.origin.client.client.render.OriginScreenRenderer.isActive()
							|| com.origin.client.client.OriginStyle.vanillaMenusOn(s)) {
						return;
					}
					com.origin.client.client.gui.Gfx g = new com.origin.client.client.gui.Gfx(poseStack);
					if (!com.origin.client.client.gui.OriginWidgetOwnership.isOriginOwnClass(s)) {
						// Minecraft's own screens (clean look, Will 2026-10-19): this version's
						// panorama, blurred, dimmed and vignetted -- except the loading screens,
						// whose mixins draw their whole scene (backdrop included) themselves.
						if (!com.origin.client.client.render.OriginScreenRenderer.drawsOwnBackdrop(s)) {
							com.origin.client.client.render.OriginScreenRenderer.renderMenuBackdrop(s, g, tick);
						}
						return;
					}
					// Origin's own screens (Right Shift mod menu family): unchanged rings backdrop.
					if (com.origin.client.client.render.OriginScreenRenderer.renderTitleBackground(g)) {
						// Bloom the spotlight while any visible widget is hovered (1.21.1's
						// ScreenBackgroundMixin hover test, spelled for this era).
						boolean hovering = false;
						for (var child : s.children()) {
							if (child instanceof net.minecraft.client.gui.components.AbstractWidget w
									&& w.visible && w.isMouseOver(mx, my)) {
								hovering = true;
								break;
							}
						}
						com.origin.client.client.render.OriginScreenRenderer.renderTitleCursorGlow(g, mx, my, hovering);
					}
				} catch (Throwable t) {
					// backdrop must never take the frame down
				}
			});
		});
		// Waypoints, party pings, WorldEdit CUI and Chunk Borders stay above an Iris
		// pack's clouds / fog: banked at LAST, drawn after the pack's composite
		// (render/AfterShadersPass). Shaders off: unchanged.
		com.origin.client.client.render.AfterShadersPass.init();
		// Camera rotation exactly ONCE. Fabric API 0.46.1+1.17 fires LAST right
		// before LevelRenderer.renderDebug(Camera), which sits INSIDE renderLevel's
		// final clouds/weather block: RenderSystem.getModelViewStack().pushPose();
		// mulPoseMatrix(poseStack.last().pose()); applyModelViewMatrix() -- so the
		// shader's ModelViewMat already holds the camera rotation there. Our
		// renderers bake context.matrixStack() (which ALSO holds it) into every
		// vertex, so it was applied twice: the waypoint beam/chunk-border posts
		// swung opposite to the camera, and WaypointHud.capture (ModelViewMat x
		// pose) projected the labels with a doubled rotation. For our draws the
		// model-view is set to IDENTITY -- the state vanilla flushes entities and
		// the hit outline under -- and restored afterwards. Everything is flushed
		// inside the window (OriginRenderTypes.draw / the shared buffer's endBatch
		// in each renderer).
		WorldRenderEvents.LAST.register(context -> {
			com.mojang.blaze3d.vertex.PoseStack modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
			modelView.pushPose();
			// last().pose()/normal().setIdentity(): Matrix4f/Matrix3f.setIdentity
			// exist on every era (1.16 GameRenderer uses them); PoseStack's own
			// setIdentity() is not needed.
			modelView.last().pose().setIdentity();
			modelView.last().normal().setIdentity();
			com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
			try {
				try {
					boolean bank = com.origin.client.client.render.AfterShadersPass.bankBegin();   // shaders on: drawn after the pack's clouds
					try {
						ChunkBorderRenderer.render(context);
					} finally {
						com.origin.client.client.render.AfterShadersPass.bankEnd(bank);
					}
				} catch (Throwable t) {
					// overlay must never take the frame down
				}
				try {
					boolean bank = com.origin.client.client.render.AfterShadersPass.bankBegin();   // shaders on: drawn after the pack's clouds
					try {
						com.origin.client.client.waypoints.WaypointRenderer.render(context);
					} finally {
						com.origin.client.client.render.AfterShadersPass.bankEnd(bank);
					}
				} catch (Throwable t) {
					// waypoints must never take the frame down
				}
				// Friends & Party: ping beams + the camera capture for the party markers
				// (also with Waypoints off). Fail-soft inside.
				com.origin.client.client.social.Social.renderWorld(context);
				// Cosmetic sparkles (cosmetics/CosmeticEffects): drawn here, inside the
				// identity window, for the same reason. Fail-soft inside.
				com.origin.client.client.cosmetics.CosmeticEffects.renderWorld(context);
			} finally {
				modelView.popPose();
				com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
			}
		});
		// Pause menu "Mods | Origin" row (Will, 2026-11-04; addPauseModsRow). Mod Menu adds its
		// own "Mods" button from its AFTER_INIT listener and the order between mods' listeners is
		// not fixed, so the row is built once per init at the first frame, after every listener has run.
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
				final boolean[] built = {false};
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.beforeRender(screen).register((s, g, mx, my, tick) -> {
					if (!built[0]) {
						built[0] = true;
						addPauseModsRow(client, s);
					}
				});
			}
		});
		// Potion Effects "Show In Inventory": the HUD pass is skipped while a
		// container screen is open, so draw the potion element over it here.
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) {
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register((s, poseStack, mx, my, tick) -> {
					if (Mods.on("potionhud") && Mods.bool("potionhud", "showInInventory")) {
						com.origin.client.client.hud.HudElements.renderInInventory(
								new com.origin.client.client.gui.Gfx(poseStack));
					}
				});
			}
		});
		// NO HUD hook over the Origin mod menu (removed 2026-10-11). On 1.17.1
		// GameRenderer.render still runs Gui.render (-> GuiHudMixin -> renderAll)
		// every in-world frame BEFORE the open screen draws (vanilla's
		// `!hideGui || screen != null` branch), so the HUD already sits behind the
		// menu and live-previews toggles there. A second afterRender pass drew the
		// whole HUD again ON TOP of the menu (Will's call: the HUD always stays
		// behind menus, even hidden behind the card).
		// WorldEdit CUI: its own plugin channel, tick and world-render hooks (fail-soft inside).
		com.origin.client.client.worldedit.WorldEditCui.init();
		// Origin originals: Portal Calculator hooks its own client tick (fail-soft inside).
		com.origin.client.client.mods.PortalCalc.init();
		// FPS Coach (Origin original): its own tick + per-frame hooks (fail-soft inside).
		com.origin.client.client.mods.FpsCoach.register();
		// Lunar parity, batch 3a: Auto GG's chat listeners and the Screenshot
		// chat buttons' click hook (both Fabric API events, fail-soft).
		try {
			com.origin.client.client.mods.AutoGG.register();
			com.origin.client.client.mods.ScreenshotChat.register();
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: chat/screenshot hooks failed to register", t);
		}
		// Cosmetics bought in the Origin Store (docs/STORE.md): the service
		// client (background thread, fail-soft), the data-driven model layer
		// (hats, masks, wings, backpacks) on every player model, and the menu
		// theme. Capes ride vanilla's cape slot (CosmeticCapeMixin); the sparkles
		// draw from the LAST hook above.
		// Fabric API 0.46.1+1.17 has the feature-renderer callback with the
		// EntityRendererProvider.Context argument (1.17's renderer factories).
		try {
			com.origin.client.client.cosmetics.CosmeticsService.start();
			com.origin.client.client.theme.ThemeManager.register();
			net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register(
					(type, renderer, helper, ctx) -> {
						if (renderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer player) {
							helper.register(new com.origin.client.client.cosmetics.CosmeticLayer(player));
						}
					});
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: cosmetics failed to start", t);
		}
		// Friends, chat and parties (docs/SOCIAL.md): the service client (background
		// thread, fail-soft), its tick / toast-click hooks and /originpc. Its world
		// pass runs from the LAST hook above (inside the identity model-view window).
		try {
			com.origin.client.client.social.Social.init();
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: friends failed to start", t);
		}
		// Block Outline + Overlay (own colour/width + translucent fill).
		net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.BLOCK_OUTLINE.register((wctx, octx) -> {
			try {
				return com.origin.client.client.mods.BlockOverlayRenderer.onBlockOutline(wctx, octx);
			} catch (Throwable t) {
				return true; // fail-soft: let vanilla draw its outline
			}
		});
	}

	// Lunar parity, batch 3a (chat / server): per-tick work. Each in its own
	// try so one mod failing never stops the others or the client tick. Runs
	// before the no-player return: Nick Hider and Screenshot work on menus too.
	private static void tickLunarBatch3a(Minecraft client) {
		try {
			com.origin.client.client.mods.AutoTextHotkey.tick(client);
		} catch (Throwable t) {
			// hotkeys just don't fire this tick
		}
		try {
			com.origin.client.client.mods.AutoGG.tick(client);
		} catch (Throwable t) {
			// a pending gg is dropped at worst
		}
		try {
			com.origin.client.client.mods.NickHider.tick(client);
		} catch (Throwable t) {
			// names keep their last mapping
		}
		try {
			com.origin.client.client.mods.Quickplay.tick(client);
		} catch (Throwable t) {
			// keys just don't fire this tick
		}
		try {
			com.origin.client.client.mods.ScreenshotChat.tick(client);
		} catch (Throwable t) {
			// key just doesn't fire this tick
		}
	}

	// Flush everything to disk once, when Minecraft is shutting down. Each
	// store is saved in its own try so a failure in one never blocks the
	// others, and nothing here is allowed to throw into the shutdown path.
	private void onClientStopping(Minecraft client) {
		try {
			// a slider mid-drag or a half-typed text field in the mod menu
			if (client.screen instanceof com.origin.client.client.gui.OriginModMenuScreen) {
				((com.origin.client.client.gui.OriginModMenuScreen) client.screen).commitPendingEdits();
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to keep the mod menu's last edit on exit", t);
		}
		try {
			// a "Try it" still running (or unanswered) must not stay applied after quitting
			com.origin.client.client.mods.FpsCoach.restoreHeld();
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to put back an FPS Coach try on exit", t);
		}
		try {
			Mods.flush();                     // mod settings + HUD positions -> originclient-mods.json
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to save mod settings on exit", t);
		}
		try {
			OriginConfig.save(FEATURES);      // legacy feature flags -> originclient.json
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to save feature flags on exit", t);
		}
		try {
			if (client.options != null) {
				// quitting mid-zoom: write the player's own values, not Zoom's
				if (zoomSensApplied) {
					client.options.sensitivity = savedSensitivity;
					zoomSensApplied = false;
				}
				if (smoothCamApplied) {
					client.options.smoothCamera = savedSmoothCam;
					smoothCamApplied = false;
				}
				client.options.save();        // vanilla game options -> options.txt
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: failed to save game options on exit", t);
		}
	}

	/** Bookkeeping for one key that lives both in the mod menu and in Controls. */
	private static final class KeyLink {
		boolean init;
		int lastOurs = -1, lastMc = -1;
	}

	/**
	 * Zoom and Freelook each have ONE key, shown in two places: the mod menu's
	 * key row and Minecraft's Controls. They used to be two separate keys that
	 * both worked (Zoom was C twice), so rebinding one left the old key still
	 * zooming. Same rules as the Tab key sync above: a rebind on either side is
	 * copied to the other. On the first tick Controls wins, unless Controls has
	 * no key there (Freelook's Controls entry started unbound) -- then the mod
	 * menu's key is copied into Controls. A mouse button set in Controls works
	 * through the Controls entry (the mod menu only holds keyboard keys, so it
	 * shows None), and a key picked in the mod menu then replaces it.
	 */
	private boolean syncedKeyDown(Minecraft client, KeyLink link, net.minecraft.client.KeyMapping mapping,
			String modId, String option) {
		// Both sides in Origin's key format (util/KeyCodes): a mouse button in Controls
		// is stored as a mouse code (so it reaches every version through Settings Sync)
		// and fires through the Controls entry's isDown().
		int mc = bindCode(net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(mapping));
		int ours = Mods.keyCode(modId, option);
		if (!link.init) {
			link.init = true;
			if (mc == KeyCodes.UNBOUND && ours != KeyCodes.UNBOUND) {
				mc = pushKey(client, mapping, ours);
			} else if (ours != mc) {
				Mods.set(modId, option, mc);              // Controls wins the first tick
				ours = Mods.keyCode(modId, option);
			}
		} else if (ours != link.lastOurs && ours != mc) {
			mc = pushKey(client, mapping, ours);          // rebound in the mod menu (replaces a mouse bind too)
		} else if (mc != link.lastMc && mc != ours) {
			Mods.set(modId, option, mc);                  // rebound in Controls
			ours = Mods.keyCode(modId, option);
		}
		link.lastOurs = ours;
		link.lastMc = mc;
		return KeyCodes.isMouse(mc) ? mapping.isDown() : isRawKeyDown(ours);
	}

	private static int pushKey(Minecraft client, net.minecraft.client.KeyMapping mapping, int key) {
		mapping.setKey(bindKey(key));
		net.minecraft.client.KeyMapping.resetMapping();
		client.options.save();
		return key > 0 || KeyCodes.isMouse(key) ? key : KeyCodes.UNBOUND;
	}

	/** A Controls binding in Origin's key format (util/KeyCodes): its keyboard key, its
	 *  mouse button, or UNBOUND (unbound, or a scancode-only key). */
	private static int bindCode(InputConstants.Key key) {
		if (key.getType() == InputConstants.Type.MOUSE) {
			return KeyCodes.mouse(key.getValue());
		}
		return key.getType() == InputConstants.Type.KEYSYM && key.getValue() > 0 ? key.getValue() : KeyCodes.UNBOUND;
	}

	/** The Controls binding for an Origin key code; InputConstants.UNKNOWN when unbound. */
	private static InputConstants.Key bindKey(int code) {
		if (KeyCodes.isMouse(code)) {
			return InputConstants.Type.MOUSE.getOrCreate(KeyCodes.mouseButton(code));
		}
		return code > 0 ? InputConstants.Type.KEYSYM.getOrCreate(code) : InputConstants.UNKNOWN;
	}

	/**
	 * The pause menu's "Mods | Origin" row (Will, 2026-11-04): "Mods" opens the player's full mod list
	 * with each mod's settings (Mod Menu's, gui/PlayerModList), "Origin" opens Origin's own menu: the
	 * same pair as the title screen. The row takes the slot of Mod Menu's own "Mods" button, which it
	 * replaces (any other Mod Menu entry on this screen goes too: one way in); with no Mod Menu button,
	 * the Send Feedback | Report Bugs row that Mod Menu itself replaces by default, or, where those
	 * are small icons (26.x), a new full-width row above them. Fail-soft: a surprise leaves the menu
	 * as it was.
	 */
	private static void addPauseModsRow(Minecraft client, net.minecraft.client.gui.screens.Screen pause) {
		try {
			java.util.List<net.minecraft.client.gui.components.AbstractWidget> buttons = net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(pause);
			java.util.List<net.minecraft.client.gui.components.AbstractWidget> gone = new java.util.ArrayList<>();
			net.minecraft.client.gui.components.AbstractWidget slot = null, feedback = null, bugs = null, options = null, wide = null;
			String feedbackLabel = net.minecraft.client.resources.language.I18n.get("menu.sendFeedback");
			String bugsLabel = net.minecraft.client.resources.language.I18n.get("menu.reportBugs");
			String optionsLabel = net.minecraft.client.resources.language.I18n.get("menu.options");
			for (net.minecraft.client.gui.components.AbstractWidget w : buttons) {
				String label = w.getMessage().getString();
				if (com.origin.client.client.gui.PlayerModList.isModMenuButton(w, label)) {
					gone.add(w);
					if (slot == null && w.getWidth() >= 40) {
						slot = w;   // its wide row; Mod Menu's narrow icon style is too small for two labels
					}
				} else if (feedbackLabel.equals(label)) {
					feedback = w;
				} else if (bugsLabel.equals(label)) {
					bugs = w;
				} else if (optionsLabel.equals(label)) {
					options = w;
				}
				// Buttons only: since 1.20.2 the screen's "Game Menu" title is a screen-wide StringWidget,
				// which would otherwise become the template (a 9 px row at x=0 under everything).
				if (w instanceof net.minecraft.client.gui.components.Button
						&& (wide == null || w.getWidth() > wide.getWidth())) {
					wide = w;   // a full-width row (Back to Game): the template for an inserted row
				}
			}
			int x, y, width, height;
			// Vanilla's pause columns sit 8 px apart on every version (two 98s in a 204 row).
			int gap = 8;
			if (slot != null) {
				x = slot.x;
				y = slot.y;
				width = slot.getWidth();
				height = slot.getHeight();
			} else if (feedback != null && bugs != null
					&& Math.max(feedback.x + feedback.getWidth(), bugs.x + bugs.getWidth()) - Math.min(feedback.x, bugs.x) >= 150) {
				// the classic two-button Send Feedback | Report Bugs row (26.x shrank them to icons: below)
				x = Math.min(feedback.x, bugs.x);
				y = feedback.y;
				width = Math.max(feedback.x + feedback.getWidth(), bugs.x + bugs.getWidth()) - x;
				height = feedback.getHeight();
				gone.add(feedback);
				gone.add(bugs);
			} else {
				// No such row (26.x's feedback/bugs are small icons, kept): a new full-width row goes in
				// above the feedback icons (or Options), and every row from there moves down one step.
				net.minecraft.client.gui.components.AbstractWidget anchor = feedback != null ? feedback : options;
				if (anchor == null || wide == null) {
					return;   // no place found: the menu stays as it is
				}
				int top = anchor.y;
				int above = Integer.MIN_VALUE;
				for (net.minecraft.client.gui.components.AbstractWidget w : buttons) {
					if (w.y + w.getHeight() <= top) {
						above = Math.max(above, w.y + w.getHeight());
					}
				}
				int rowGap = above == Integer.MIN_VALUE ? 4 : top - above;   // the menu's own row spacing
				x = wide.x;
				y = top;
				width = wide.getWidth();
				height = wide.getHeight();
				for (net.minecraft.client.gui.components.AbstractWidget w : buttons) {
					if (w.y >= top) {
						w.y = w.y + height + rowGap;
					}
				}
			}
			for (net.minecraft.client.gui.components.AbstractWidget w : gone) {
				int i = buttons.indexOf(w);
				if (i >= 0) {
					buttons.remove(i);
				}
			}
			int half = (width - gap) / 2;
			buttons.add(new net.minecraft.client.gui.components.Button(x, y, half, height, new net.minecraft.network.chat.TextComponent("Mods"),
					b -> client.setScreen(com.origin.client.client.gui.PlayerModList.screen(pause))));
			buttons.add(new net.minecraft.client.gui.components.Button(x + width - half, y, half, height, new net.minecraft.network.chat.TextComponent("Origin"),
					b -> client.setScreen(new com.origin.client.client.gui.OriginModMenuScreen(pause, null))));
		} catch (Throwable t) {
			// the pause menu stays as it was
		}
	}

	/**
	 * A press of an Origin toggle key since the last tick, outside screens (util/KeyTaps,
	 * G121-7): a tap that went down and up between two ticks is never "down" when the tick
	 * reads the key, so the toggles also ask this. Call it every tick, first in the
	 * condition, so a press is spent on that tick (two reads of one press = one toggle).
	 */
	private static boolean tapped(Minecraft client, int glfwKey) {
		return com.origin.client.client.util.KeyTaps.consume(glfwKey) && client.screen == null;
	}

	/** Raw GLFW key state for the mod-menu's rebindable keys. */
	public static boolean isRawKeyDown(int glfwKey) {
		if (glfwKey <= 0) {
			return false;
		}
		try {
			boolean down = InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), glfwKey);
			// F3+B, F3+G ... are vanilla's debug combos: a key pressed while F3 was held never
			// drives an Origin feature, even if F3 is let go first (util/KeyTaps, BMOD-1).
			return down && !com.origin.client.client.util.KeyTaps.inF3Combo(glfwKey, down);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * True while Toggle Sprint is engaged -- SprintKeyMixin then reports the
	 * vanilla sprint key as held, so the GAME starts the sprint itself, at the
	 * exact point in the tick it normally would.
	 *
	 * That is what gives the toggle its memory. A screen, a pause, a server hop
	 * or a wall bump ends the sprint exactly the way vanilla always did, and
	 * vanilla re-starts it on the first tick the player is moving again -- so it
	 * never needs re-toggling; only an un-toggle turns it off. Every vanilla
	 * rule stays in force too: no sprinting inside a screen, at low hunger, or
	 * while eating/blocking.
	 *
	 * Toggle Sneak works the same way on the Sneak key (keyShift). It used to call
	 * player.setShiftKeyDown(true), but LocalPlayer.isShiftKeyDown() reads the tick's
	 * Input, which KeyboardInput builds from keyShift.isDown() -- so the server never
	 * saw a sneak: edges weren't safe and shift-placing against a chest opened it.
	 * Now the Sneak key reads as held and vanilla sneaks (client and server) itself.
	 */
	public static boolean keyHeldByToggle(net.minecraft.client.KeyMapping key) {
		if ((!FEATURES.sprintToggledOn && !FEATURES.sneakToggledOn) || readingRealSneakKey) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.options == null) {
			return false;
		}
		if (mc.screen != null) {
			return false;   // vanilla releases every key in a screen; don't lie to it
		}
		if (!Mods.on("togglesprint")) {
			return false;
		}
		if (key == mc.options.keySprint) {
			return FEATURES.sprintToggledOn && Mods.bool("togglesprint", "sprint");
		}
		if (key == mc.options.keyShift) {
			return FEATURES.sneakToggledOn && Mods.bool("togglesprint", "sneak");
		}
		return false;
	}

	/** The player's Sneak key as a GLFW keyboard key, or -1 when it is a mouse button
	 *  (a mouse bind is read through its KeyMapping in sneakKeyDown instead). */
	private static int sneakKeyboardKey(Minecraft client) {
		try {
			InputConstants.Key bound = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(client.options.keyShift);
			return bound.getType() == InputConstants.Type.KEYSYM ? bound.getValue() : -1;
		} catch (Throwable t) {
			return -1;
		}
	}

	/** Physical state of the player's Sneak key (Controls' "Sneak"), read raw so
	 *  vanilla's own Sneak: Toggle option and key clashes can't hide a press. A
	 *  mouse-button bind can't go through glfwGetKey (0-7 are not key codes: GLFW
	 *  logs "Invalid key" and the toggle never fires), so it reads the mapping. */
	private static boolean sneakKeyDown(Minecraft client) {
		try {
			InputConstants.Key bound = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(client.options.keyShift);
			if (bound.getType() == InputConstants.Type.MOUSE) {
				readingRealSneakKey = true;   // the player's own press, not the toggle's "held"
				try {
					return client.options.keyShift.isDown();
				} finally {
					readingRealSneakKey = false;
				}
			}
			return isRawKeyDown(bound.getValue());
		} catch (Throwable t) {
			return false;
		}
	}

	/** Dev-only boot-smoke marker (env ORIGIN_SMOKE, set by tools/boot-smoke/smoke.py): pending until the title screen is first up. */
	private static boolean smokeTitlePending = System.getenv("ORIGIN_SMOKE") != null;
	/** Dev-only in-world smoke step (env ORIGIN_SMOKE_WORLD = shots dir, smoke.py --inworld): after the title marker,
	 *  OriginDevWorld.inWorldTick enters a flat world, shoots the HUD and the Mod Menu, and Saves and Quits. */
	private static final String SMOKE_WORLD_DIR = System.getenv("ORIGIN_SMOKE") != null ? System.getenv("ORIGIN_SMOKE_WORLD") : null;

	private void onEndTick(Minecraft client) {
		// boot-smoke's "ready" = the title screen really reached (not just GL up). Players never set ORIGIN_SMOKE.
		if (smokeTitlePending && client.getOverlay() == null && client.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
			smokeTitlePending = false;
			com.origin.client.OriginClient.LOGGER.info("[origin-smoke] title screen reached");
		}
		// Dev-only screenshot matrix (env ORIGIN_SHOTS) — see OriginDevShots.
		if (OriginDevShots.enabled()) {
			OriginDevShots.tick(client);
		} else if (SMOKE_WORLD_DIR != null && !smokeTitlePending) {
			OriginDevWorld.inWorldTick(client, SMOKE_WORLD_DIR, () -> {
			});
		}
		// Right Shift opens the mod menu (the screen itself closes on the
		// same key or Esc, with the reversed slide).
		// Right Shift opens the quick HUD-edit screen (drag/resize elements
		// directly, ORIGIN logo, dark MODS button into the full grid).
		while (OriginKeyBindings.openModMenu.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new com.origin.client.client.hud.HudEditorScreen(true));
			}
		}

		// Copy Coordinates: press the (unbound-by-default) key to drop your XYZ on
		// the clipboard -- any time you're in a world, Coordinates HUD on or off
		// (same as 1.21.1; the card's old "Copy Coords To Clipboard" switch is gone,
		// an old config's copyClipboard value is simply no longer read).
		while (OriginKeyBindings.copyCoords.consumeClick()) {
			LocalPlayer p = client.player;
			if (p != null) {
				String c = p.blockPosition().getX() + ", " + p.blockPosition().getY() + ", " + p.blockPosition().getZ();
				client.keyboardHandler.setClipboard(c);
				p.displayClientMessage(new net.minecraft.network.chat.TextComponent("Copied coordinates: " + c), true);
			}
		}

		// Waypoints — three activation paths (in addition to the mod-card ENABLED
		// toggle and the in-menu toggle in WaypointScreen).
		while (OriginKeyBindings.waypointToggle.consumeClick()) {
			Mods.setOn("waypoints", !Mods.on("waypoints"));
		}
		while (OriginKeyBindings.waypointMenu.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new com.origin.client.client.waypoints.WaypointScreen());
			}
		}
		while (OriginKeyBindings.waypointCreate.consumeClick()) {
			// Opens the Waypoints screen straight into a fresh Create form.
			if (client.screen == null) {
				client.setScreen(new com.origin.client.client.waypoints.WaypointScreen(true));
			}
		}
		// Death waypoint: drop one on the alive→dead transition (edge-triggered).
		LocalPlayer wp = client.player;
		boolean dead = wp != null && (wp.isDeadOrDying() || wp.getHealth() <= 0f);
		if (dead && wasAlive && Mods.on("waypoints") && Mods.bool("waypoints", "deathWaypoints")) {
			var bp = wp.blockPosition();
			com.origin.client.client.waypoints.Waypoints.onDeath(bp.getX(), bp.getY(), bp.getZ(),
					com.origin.client.client.waypoints.Waypoints.currentDim());
		}
		wasAlive = wp != null && !dead;

		applyChat(client);
		applyHitboxes(client);
		applyWeather(client);
		applyTimeChanger(client);
		GeneralSettings.tick(client);
		MotionBlur.tick(client);
		// Snaplook ticks before the no-player return so leaving a world mid-look
		// still restores the saved camera view.
		com.origin.client.client.mods.Snaplook.tick(client);

		// Lunar-parity readouts' per-tick state (Stopwatch keys, Ping samples,
		// Combo/Reach hit confirmation). Fail-soft: a HUD readout must never be
		// able to throw out of the client tick.
		try {
			com.origin.client.client.mods.Stopwatch.tick(client);
			com.origin.client.client.mods.PingSampler.tick(client);
			com.origin.client.client.mods.CombatStats.tick(client);
			com.origin.client.client.minimap.MinimapHud.tick(client);   // zoom / full-map keys, auto cave mode
		} catch (Throwable t) {
			// keep ticking; the readouts just hold their last value
		}
		tickLunarBatch3a(client);

		LocalPlayer player = client.player;
		if (player == null) {
			releaseCameraMods(client);   // left the world: Zoom / Freelook end, settings come back
			return;
		}

		// Scoreboard hide toggle keybind (+ optional action-bar message)
		boolean sbDown = client.screen == null && isRawKeyDown(Mods.keyCode("scoreboard", "toggleKey"));
		if (tapped(client, Mods.keyCode("scoreboard", "toggleKey")) || (sbDown && !scoreboardKeyWasDown)) {
			boolean hidden = !Mods.bool("scoreboard", "hideScoreboard");
			Mods.set("scoreboard", "hideScoreboard", hidden);
			if (Mods.bool("scoreboard", "displayToggleMessage")) {
				player.displayClientMessage(new net.minecraft.network.chat.TextComponent(
						hidden ? "Scoreboard hidden" : "Scoreboard shown"), true);
			}
		}
		scoreboardKeyWasDown = sbDown;

		// Full Bright toggle keybind flips the mod's own sub-toggle
		boolean fbDown = client.screen == null && isRawKeyDown(Mods.keyCode("fullbright", "key"));
		if (tapped(client, Mods.keyCode("fullbright", "key")) || (fbDown && !fullbrightKeyWasDown)) {
			// With the Lighting mod off the key used to flip a hidden setting and nothing
			// changed on screen: now it switches Lighting on with Full Bright.
			boolean on = !Mods.on("fullbright") || !Mods.bool("fullbright", "fullBright");
			if (on && !Mods.on("fullbright")) {
				Mods.setOn("fullbright", true);
			}
			Mods.set("fullbright", "fullBright", on);
			player.displayClientMessage(new net.minecraft.network.chat.TextComponent(on ? "Full Bright on" : "Full Bright off"), true);
		}
		fullbrightKeyWasDown = fbDown;

		// Nametag toggle keybinds (default unbound). Toggle All hides every
		// nametag; Toggle Players hides only other players'.
		boolean naDown = client.screen == null && isRawKeyDown(Mods.keyCode("nametags", "toggleAll"));
		if (tapped(client, Mods.keyCode("nametags", "toggleAll")) || (naDown && !nametagAllKeyWasDown)) {
			nametagsHidden = !nametagsHidden;
			if (Mods.bool("nametags", "displayToggleMessage")) {
				player.displayClientMessage(new net.minecraft.network.chat.TextComponent(
						nametagsHidden ? "Nametags hidden" : "Nametags shown"), true);
			}
		}
		nametagAllKeyWasDown = naDown;

		boolean npDown = client.screen == null && isRawKeyDown(Mods.keyCode("nametags", "togglePlayers"));
		if (tapped(client, Mods.keyCode("nametags", "togglePlayers")) || (npDown && !nametagPlayerKeyWasDown)) {
			playerNametagsHidden = !playerNametagsHidden;
			if (Mods.bool("nametags", "displayToggleMessage")) {
				player.displayClientMessage(new net.minecraft.network.chat.TextComponent(
						playerNametagsHidden ? "Player nametags hidden" : "Player nametags shown"), true);
			}
		}
		nametagPlayerKeyWasDown = npDown;

		// Tab list — Origin drives visibility itself so it shows even on a solo local
		// server (vanilla refuses to). Holding the Player List key always shows it;
		// the Tab Editor mod adds sticky tap-to-lock + two-way keybind sync on top.
		// Both sides in Origin's key format (util/KeyCodes). A mouse-bound Player List is
		// read through its Controls entry, like sneakKeyDown: glfwGetKey only knows
		// keyboard keys, so holding a mouse-bound Player List used to show nothing.
		int mcTab = bindCode(net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
				.getBoundKeyOf(client.options.keyPlayerList));
		boolean rawTab = client.screen == null && (KeyCodes.isMouse(mcTab)
				? client.options.keyPlayerList.isDown() : isRawKeyDown(mcTab));
		if (Mods.on("tablist")) {
			int ourTab = Mods.keyCode("tablist", "tabKey");
			if (!tabSyncInit) {
				// On first tick, adopt Minecraft's Player List binding as the source
				// of truth so the two always start in agreement.
				tabSyncInit = true;
				if (ourTab != mcTab) {
					Mods.set("tablist", "tabKey", mcTab);
					ourTab = Mods.keyCode("tablist", "tabKey");
				}
			} else if (ourTab != lastOurTabKey && ourTab != mcTab) {
				// Rebound in the Origin menu → push to Minecraft's controls.
				client.options.keyPlayerList.setKey(bindKey(ourTab));
				net.minecraft.client.KeyMapping.resetMapping();
				client.options.save();
				mcTab = ourTab;
			} else if (mcTab != lastMcTabKey && mcTab != ourTab) {
				// Rebound in Minecraft's controls → pull into the Origin option.
				Mods.set("tablist", "tabKey", mcTab);
				ourTab = Mods.keyCode("tablist", "tabKey");
			}
			lastOurTabKey = ourTab;
			lastMcTabKey = mcTab;

			long now = System.currentTimeMillis();
			if (rawTab && !tabKeyWasDown) {
				tabPressAt = now;
			}
			if (!rawTab && tabKeyWasDown) {
				// Quick tap → toggle the sticky lock; a longer hold-then-release just
				// ends the vanilla peek (sticky stays off).
				if (Mods.bool("tablist", "stickyToggle") && (now - tabPressAt) < 200) {
					tabStickyOn = !tabStickyOn;
				} else {
					tabStickyOn = false;
				}
			}
		} else {
			tabStickyOn = false;
			tabSyncInit = false;
		}
		tabKeyWasDown = rawTab;
		// Held → always visible (even solo / mod off); sticky lock only with the mod on.
		tabListVisible = rawTab || (Mods.on("tablist") && tabStickyOn);

		// Toggle Zoom: in toggle mode the key latches instead of holding. The
		// FOV easing itself lives in GameRendererMixin (frame-side); here we only
		// resolve whether zoom is engaged and expose it as zoomActive.
		boolean zDown = client.screen == null
				&& syncedKeyDown(client, zoomLink, OriginKeyBindings.zoom, "zoom", "key");
		boolean zTap = tapped(client, Mods.keyCode("zoom", "key"));   // syncedKeyDown keeps it equal to Controls
		if (Mods.on("zoom") && Mods.bool("zoom", "toggleZoom")) {
			if (zTap || (zDown && !zoomKeyWasDown)) {
				zoomToggled = !zoomToggled;
			}
		} else {
			zoomToggled = false;
		}
		zoomKeyWasDown = zDown;
		zoomActive = Mods.on("zoom") && (Mods.bool("zoom", "toggleZoom") ? zoomToggled : zDown);
		// The scroll-zoom depth resets once the zoom-out ease has finished (the FOV hook),
		// not here on the release tick: resetting here made the FOV jump mid-ease.

		// Zoomed Sensitivity: temporarily scale mouse sensitivity while zoomed so
		// aiming is steadier, restoring the player's own value the instant zoom ends.
		// Not while a menu is open: Options then shows (and saves) the player's own
		// sensitivity, and a change made there is what zoom scales once it closes.
		if (zoomActive && client.screen == null) {
			if (!zoomSensApplied) {
				savedSensitivity = client.options.sensitivity;
				zoomSensApplied = true;
			}
			double f = Mods.num("zoom", "sensitivity");
			zoomedSensitivity = Math.max(0.0, Math.min(1.0, savedSensitivity * (f <= 0 ? 1.0 : f)));
			client.options.sensitivity = zoomedSensitivity;
		} else if (zoomSensApplied) {
			client.options.sensitivity = savedSensitivity;
			zoomSensApplied = false;
		}

		// Chunk borders toggle keybind (edge-triggered, ignored in screens).
		int cbKey = Mods.keyCode("chunkborders", "key");
		boolean cbDown = client.screen == null && isRawKeyDown(cbKey);
		if (tapped(client, cbKey) || (cbDown && !chunkKeyWasDown)) {
			Mods.setOn("chunkborders", !Mods.on("chunkborders"));
		}
		chunkKeyWasDown = cbDown;

		// Toggle Sprint / Sneak. "Hold" mode = vanilla behavior (we do
		// nothing); "Toggle" flips on the vanilla key or the custom bind.
		// Toggle Sneak/Sprint is one mod now (spec §5): the "sprint"/"sneak"
		// sub-toggles pick which the hands-free behavior applies to.
		// Toggle happens ONLY on the mod's assigned toggle key — we no longer
		// consume the vanilla sprint/sneak keys, so double-tap-W sprinting and
		// hold-Ctrl / hold-Shift keep working normally. We also never force the
		// state OFF: while sprint is toggled the vanilla sprint key simply reads as
		// held (SprintKeyMixin) so the game drives the sprint itself; the Sneak key reads as held the
		// same way while sneak is toggled -- vanilla stays in control the rest of
		// the time.
		boolean toggleMod = Mods.on("togglesprint");
		// Mode: "Toggle" = press once, stays on. "Hold" = active only while the
		// mod's key is held (a hands-free key that isn't the vanilla one). The
		// dropdown used to be read by nothing, so Hold behaved as Toggle.
		boolean holdMode = Mods.mode("togglesprint", "mode").equals("Hold");
		// Spend both keys' taps every tick, toggle on or off: a Ctrl/Shift press made while a
		// toggle is off must not flip sprint/sneak on by itself when the toggle is turned on.
		boolean sprintTap = tapped(client, Mods.keyCode("togglesprint", "key"));
		boolean sneakTap = tapped(client, sneakKeyboardKey(client));
		if (toggleMod && Mods.bool("togglesprint", "sprint")) {
			// Read the key every tick, screens included: closing one with the key still held
			// must not count as a new press (Shift-click in the inventory, then E).
			boolean raw = isRawKeyDown(Mods.keyCode("togglesprint", "key"));
			boolean custom = client.screen == null && raw;
			boolean edge = sprintTap || (custom && !sprintKeyWasDown);
			sprintKeyWasDown = raw;
			if (holdMode) {
				FEATURES.sprintToggledOn = custom;
			} else if (edge) {
				FEATURES.sprintToggledOn = !FEATURES.sprintToggledOn;
			}
			// The sprint itself is NOT forced from here any more. While the
			// toggle is on, SprintKeyMixin makes the vanilla sprint key read as
			// held, so the game starts (and re-starts) the sprint on its own at
			// the right point in the tick -- see keyHeldByToggle().
		} else {
			FEATURES.sprintToggledOn = false;
		}

		if (toggleMod && Mods.bool("togglesprint", "sneak")) {
			// Toggle Sneak rides the player's own Sneak key (Controls), not the
			// Sprint key: sharing that one key made one press start sprint AND
			// crouch, and the crouch then ended the sprint.
			// Read the key every tick, screens included: closing one with the key still held
			// must not count as a new press (Shift-click in the inventory, then E).
			boolean raw = sneakKeyDown(client);
			boolean custom = client.screen == null && raw;
			boolean edge = sneakTap || (custom && !sneakKeyWasDown);
			sneakKeyWasDown = raw;
			if (holdMode) {
				FEATURES.sneakToggledOn = custom;
			} else if (edge) {
				FEATURES.sneakToggledOn = !FEATURES.sneakToggledOn;
			}
			// The crouch is NOT forced from here: while the toggle is on, SprintKeyMixin
			// makes the vanilla Sneak key read as held, so the game's own input sneaks
			// and the server sees it -- see keyHeldByToggle().
		} else {
			FEATURES.sneakToggledOn = false;
		}

		// Fly Boost: multiply flight speed, but ONLY in creative and only while
		// airborne -- it never touches survival movement, so it's a QoL tweak, not a
		// speed hack. It scales the speed in effect (vanilla's, or a server's /flyspeed)
		// and restores exactly that afterwards, never a hard-coded default. Spectator is
		// left alone: there the mouse wheel sets the flight speed, and a per-tick boost
		// overwrote every scroll.
		var abilities = player.getAbilities();
		if (Mods.on("togglesprint") && Mods.bool("togglesprint", "flyBoost")
				&& abilities.flying && player.isCreative()) {
			float now = abilities.getFlyingSpeed();
			if (!flyBoostApplied || Math.abs(now - flyBoostSet) > 1.0e-6f) {
				flyBoostBase = now;   // first boosted tick, or the speed was changed by someone else
			}
			float mult = (float) Math.max(1.0, Mods.num("togglesprint", "flyBoostAmount"));
			flyBoostSet = flyBoostBase * mult;
			abilities.setFlyingSpeed(flyBoostSet);
			flyBoostApplied = true;
		} else if (flyBoostApplied) {
			if (Math.abs(abilities.getFlyingSpeed() - flyBoostSet) <= 1.0e-6f) {
				abilities.setFlyingSpeed(flyBoostBase);   // still ours: put the player's speed back
			}
			flyBoostApplied = false;
		}

		// Freelook: one key (default Left Alt), the same in the mod menu and in
		// Controls (syncedKeyDown). Hold = look around; release = snap back (the
		// player's real rotation is never touched — see MouseHandlerMixin).
		// "Toggle Freelook" turns the hold into a press-on/press-off latch.
		boolean keyDown = Mods.on("freelook") && client.screen == null
				&& syncedKeyDown(client, freelookLink, OriginKeyBindings.freelook, "freelook", "key");
		boolean freelookDown = Mods.bool("freelook", "toggle")
				? (keyDown && !freelookWasDown) != OriginFreelookState.active
				: keyDown;
		// Freelook switched off (or stepped aside for the player's own mod) mid-look: end
		// it and put the camera back, as Snaplook does. A toggled-on look used to stay on.
		if (!Mods.on("freelook")) {
			freelookDown = false;
		}
		// Hypixel bans freelook: there the key does nothing (an action-bar line on each
		// press says why) and a toggled-on freelook from before the join ends at once.
		// Gated here, where the camera detach starts, so every camera hook stays inert.
		if ((freelookDown || OriginFreelookState.active)
				&& com.origin.client.client.mods.Hypixel.bansFreelook(client)) {
			if ((keyDown && !freelookWasDown) || OriginFreelookState.active) {
				com.origin.client.client.mods.Hypixel.sayFreelookOff(client);
			}
			freelookDown = false;
		}
		if (freelookDown && !OriginFreelookState.active) {
			OriginFreelookState.cameraYaw = player.getViewYRot(1f);
			OriginFreelookState.cameraPitch = player.getViewXRot(1f);
			OriginFreelookState.active = true;
			// Third Person mode = Lunar-style orbit: jump to third person for
			// the hold and restore whatever perspective the player was in.
			if (Mods.mode("freelook", "listMode").equals("Third Person")) {
				// Snaplook engaged: take over the view IT would put back, so whichever is
				// released last restores the view from before either one started
				net.minecraft.client.CameraType before = com.origin.client.client.mods.Snaplook.handOver();
				savedPerspective = before != null ? before : client.options.getCameraType();
				client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			}
		} else if (!freelookDown && OriginFreelookState.active) {
			OriginFreelookState.active = false;
			if (savedPerspective != null) {
				// Snaplook still engaged (Toggle Mode): it shows its view again and restores this later
				if (!com.origin.client.client.mods.Snaplook.takeBack(client, savedPerspective)) {
					client.options.setCameraType(savedPerspective);
				}
				savedPerspective = null;
			}
		}
		freelookWasDown = keyDown;

		// Smooth Camera Movement (zoom / freelook / snaplook): ride vanilla's cinematic
		// camera smoothing while either is engaged, then restore the player's own
		// setting the moment both release.
		boolean wantSmooth = (zoomActive && Mods.bool("zoom", "smoothCamera"))
				|| (OriginFreelookState.active && Mods.on("freelook") && Mods.bool("freelook", "smoothCamera"))
				|| com.origin.client.client.mods.Snaplook.wantsSmoothCamera();
		if (wantSmooth && !smoothCamApplied) {
			savedSmoothCam = client.options.smoothCamera;
			client.options.smoothCamera = true;
			smoothCamApplied = true;
		} else if (!wantSmooth && smoothCamApplied) {
			client.options.smoothCamera = savedSmoothCam;
			smoothCamApplied = false;
		}
		// Zoom's sensitivity and smooth camera are not the player's settings:
		// options.txt waits until they are put back.
		OptionsAutosave.held = zoomSensApplied || smoothCamApplied;
	}

	/** No player (left the world, title screen): ends Toggle Zoom / Toggle Freelook and puts
	 *  back what they swapped in -- the player's own sensitivity, smooth camera and view.
	 *  Each step only acts while something is applied, so ticking it costs a few checks. */
	private void releaseCameraMods(Minecraft client) {
		zoomToggled = false;
		zoomActive = false;
		zoomScrollFactor = 1.0;
		if (zoomSensApplied) {
			client.options.sensitivity = savedSensitivity;
			zoomSensApplied = false;
		}
		if (OriginFreelookState.active) {
			OriginFreelookState.active = false;
			if (savedPerspective != null) {
				client.options.setCameraType(savedPerspective);
				savedPerspective = null;
			}
		}
		if (smoothCamApplied) {
			client.options.smoothCamera = savedSmoothCam;
			smoothCamApplied = false;
		}
		OptionsAutosave.held = false;
	}

	// Weather Changer: forces client-side rain/thunder levels per the mode.
	// Snow mode renders as snowfall in cold biomes (that's how MC does snow);
	// Clear additionally skips the precipitation render pass entirely
	// (LevelRendererMixin). Server weather/gameplay is never touched.
	private void applyWeather(Minecraft client) {
		com.origin.client.client.mods.WeatherOverride.tick();   // Snow flag for LevelRendererMixin
		if (!Mods.on("weather") || client.level == null) {
			return;
		}
		// NOTE: no setRainLevel/setThunderLevel here any more.
		//
		// Writing those every tick mutated real level state, so the mod wasn't
		// purely visual, whatever it forced stayed stuck when you turned it off
		// (nothing restored it), and vanilla's own updates fought the writes so
		// switching lagged instead of snapping. The values are now produced on READ
		// by LevelWeatherMixin -> WeatherOverride, which makes switching instant,
		// leaves nothing behind, and never touches the server's weather.
		String mode = Mods.mode("weather", "mode");
		boolean thunder = com.origin.client.client.mods.WeatherOverride.thunderOn(mode);

		// Play Thunder Sounds: forcing the thunder level alone never actually
		// makes a sound (vanilla thunder rides real lightning bolts we don't
		// spawn), so play a rolling thunder clip on a gentle cadence instead.
		if (thunder && Mods.bool("weather", "playThunderSounds") && client.player != null) {
			if (++thunderSoundTicks >= 300) {   // ~every 15 seconds
				thunderSoundTicks = 0;
				client.level.playLocalSound(client.player.getX(), client.player.getY(), client.player.getZ(),
						net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER,
						net.minecraft.sounds.SoundSource.WEATHER, 1.0f, 0.9f, false);
			}
		} else {
			thunderSoundTicks = 0;
		}
	}

	// Time Changer: slider (live), Use Real Current Time, +/- keybinds, and
	// Time Passage all resolve into one timeOverride that LevelTimeMixin reads.
	private void applyTimeChanger(Minecraft client) {
		if (!Mods.on("timechanger")) {
			return;
		}
		double base = Mods.num("timechanger", "time");
		if (Mods.bool("timechanger", "useRealTime")) {
			var now = java.time.LocalTime.now();
			// noon IRL = noon in-game: 06:00 IRL maps to tick 0 (sunrise)
			base = ((now.getHour() * 1000.0 + now.getMinute() * 1000.0 / 60.0) - 6000 + 24000) % 24000;
		}
		boolean inc = client.screen == null && isRawKeyDown(Mods.keyCode("timechanger", "increaseKey"));
		boolean dec = client.screen == null && isRawKeyDown(Mods.keyCode("timechanger", "decreaseKey"));
		if (((inc && !timeIncWasDown) || (dec && !timeDecWasDown)) && Mods.bool("timechanger", "useRealTime")) {
			// [ / ] with Use Real Time on: the real clock rewrote the time every tick, so the
			// keys did nothing. Switch it off (saved) and step from the time shown right now.
			Mods.set("timechanger", "useRealTime", false);
		}
		if (inc && !timeIncWasDown) {
			base = (base + 500) % 24000;
			Mods.set("timechanger", "time", base);
		}
		if (dec && !timeDecWasDown) {
			base = (base - 500 + 24000) % 24000;
			Mods.set("timechanger", "time", base);
		}
		timeIncWasDown = inc;
		timeDecWasDown = dec;
		if (Mods.bool("timechanger", "timePassage")) {
			// speed 1.0 = vanilla day speed (+1 tick per tick), kept transient
			// so it never spams config saves
			timePassageAccum = (timePassageAccum + Mods.num("timechanger", "speed")) % 24000;
		} else {
			timePassageAccum = 0;
		}
		timeOverride = (base + timePassageAccum) % 24000;
	}

	// Live sky while dragging the Time slider in the mod menu. applyTimeChanger
	// only runs per client tick (20x/sec), so the sky visibly stepped behind the
	// thumb; the menu calls this on every drag event instead. Same resolution as
	// applyTimeChanger minus the +/- keys. Use Real Current Time ignores the
	// slider there, so it ignores it here too (else the sky would flicker
	// between the two every tick). Client-side only, like the rest of it.
	public static void previewTimeSlider(double time) {
		if (Mods.bool("timechanger", "useRealTime")) {
			return;
		}
		timeOverride = (time + timePassageAccum) % 24000;
	}

	// Chat options ride the vanilla accessibility options. IMPORTANT mapping:
	// "Background Opacity" drives textBackgroundOpacity — the grey box behind
	// chat lines — NOT chatOpacity (which fades the TEXT itself; wiring the
	// slider there made messages unreadable). Text stays fully visible at
	// background 0. chatOpacity is pinned to 1.0 while the mod is on.
	//
	// These are the PLAYER's Minecraft chat settings, borrowed while the mod is
	// on: all three are put back when it turns off, and options.txt never sees
	// Origin's values (chatOptionsForSave, called around every Options.save()).
	private void applyChat(Minecraft client) {
		boolean on = Mods.on("chat");
		if (on) {
			if (!chatApplied) {
				savedChatOpacity = client.options.textBackgroundOpacity;
				savedChatScale = client.options.chatScale;
				savedChatTextOpacity = client.options.chatOpacity;
				chatApplied = true;
			}
			client.options.textBackgroundOpacity = Mods.num("chat", "opacity");
			client.options.chatOpacity = 1.0;
			client.options.chatScale = Mods.num("chat", "scale");
		} else if (chatApplied) {
			client.options.textBackgroundOpacity = savedChatOpacity;
			client.options.chatScale = savedChatScale;
			client.options.chatOpacity = savedChatTextOpacity;
			chatApplied = false;
		}
	}

	/**
	 * OptionsSaveMixin: true = put the player's own chat values in for the write
	 * (Options.save() HEAD), false = Origin's back after it (RETURN). A no-op
	 * while the Chat mod isn't overriding anything.
	 */
	// Zoom's lowered sensitivity and the smooth camera are Origin's values, never the
	// player's: every options.txt write (vanilla's screens, F11, the key sync, the
	// autosave) gets the player's own for the length of the write, so a crash or a
	// killed game mid-zoom can't leave them in options.txt for Settings Sync to spread.
	private static void cameraOptionsForSave(net.minecraft.client.Options options, boolean playerValues) {
		if (zoomSensApplied) {
			options.sensitivity = playerValues ? savedSensitivity : zoomedSensitivity;
		}
		if (smoothCamApplied) {
			options.smoothCamera = playerValues ? savedSmoothCam : true;
		}
	}

	public static void chatOptionsForSave(net.minecraft.client.Options options, boolean playerValues) {
		if (options != null) {
			cameraOptionsForSave(options, playerValues);   // the same save hooks (OptionsSaveMixin)
		}
		if (!chatApplied || options == null) {
			return;
		}
		if (playerValues) {
			options.textBackgroundOpacity = savedChatOpacity;
			options.chatScale = savedChatScale;
			options.chatOpacity = savedChatTextOpacity;
		} else {
			options.textBackgroundOpacity = Mods.num("chat", "opacity");
			options.chatOpacity = 1.0;
			options.chatScale = Mods.num("chat", "scale");
		}
	}

	private void applyHitboxes(Minecraft client) {
		boolean on = Mods.on("hitboxes");
		if (on != hitboxesApplied) {
			client.getEntityRenderDispatcher().setRenderHitBoxes(on);
			hitboxesApplied = on;
		}
	}
}
