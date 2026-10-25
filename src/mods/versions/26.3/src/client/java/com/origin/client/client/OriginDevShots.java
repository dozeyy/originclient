package com.origin.client.client;

import com.origin.client.client.gui.OriginModMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import com.origin.client.client.hud.HudEditorScreen;
import org.lwjgl.sdl.SDLVideo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DEV-ONLY visual verification harness (2026-09). Off unless the environment
 * variable {@code ORIGIN_SHOTS} names a directory. When it does, the client
 * boots to the title screen, then walks a matrix of window sizes × GUI scales,
 * capturing the TITLE (after its entrance animation) and the MOD MENU at each,
 * into {@code $ORIGIN_SHOTS/screenshots/}, and finally quits. With
 * {@code ORIGIN_SHOTS_SET=design} it instead captures title, mod menu, a mod's
 * settings page and the HUD editor at two sizes (.github/workflows/dev-shots.yml). This is how a
 * layout change gets LOOKED AT on every aspect ratio before it reaches Will —
 * no more "compiles, ships, looks wrong".
 *
 * Runs on the client tick (render thread). Each step waits long enough for
 * the window to actually resize and for entrance animations to settle.
 * Never touches config; the GUI-scale option is restored at the end.
 *
 * PER-VERSION DELTA (1.21.11): two calls differ from 1.21.1, both swapped for
 * ones this module already uses elsewhere --
 *   * the window is resized with SDLVideo.SDL_SetWindowSize on Window.handle() (26.3: SDL3)
 *     (the 1.21.9 Window API; handle() is what GeneralSettings uses) instead
 *     of Window.setWindowed -- the same call setWindowed makes for a windowed
 *     game, and the framebuffer callback + resizeDisplay() pick it up;
 *   * the capture is Screenshot.grab(File, RenderTarget, Consumer), the
 *     overload F2 calls (ScreenshotKeyMixin targets it), which names the file
 *     by date and writes it on the IO thread. The extra phase 3 waits for that
 *     new PNG in screenshots/ and renames it to the shot's name, so the output
 *     folder matches every other module's.
 */
public final class OriginDevShots {
	private static final String DIR = System.getenv("ORIGIN_SHOTS");

	/** What a shot opens before capturing. */
	private enum Kind {
		TITLE, MENU, MENU_LIST, MOD_SETTINGS, SETTINGS, RSHIFT, COSMETICS,
		// every other Origin window (the 2026-10-12 restyle onto gui/OriginWindow)
		FPSCOACH, WAYPOINTS, ITEMSIZE, ITEMCOUNTER, QUICKPLAY, SHADERS,
		// the mod menu with "Solid Menu Background" off (the see-through mode)
		MENU_CLEAR,
		// Friends (social/SocialDemo fills it): a chat, then the party
		FRIENDS, FRIENDS_PARTY,
		// ORIGIN_SHOTS_SET=social (the 2026-10-18 social redesign): every Friends state, the toasts, the entry points
		SOC_CHAT, SOC_PARTY, SOC_HOME, SOC_OFFLINE, SOC_EMPTY, SOC_SIGNEDOUT, SOC_UNREACHABLE,
		SOC_STRESS_CHAT, SOC_STRESS_PARTY, SOC_TOASTS, SOC_RSHIFT, SOC_MENU,
		// the redesign's new states: add-by-name row, filtered list, the "..." menu, the invite picker, a result pill
		SOC_ADD, SOC_FILTER, SOC_MENU_FRIEND, SOC_MENU_INVITE, SOC_NOTICE,
		// ORIGIN_SHOTS_SET=social-act: scripted synthetic input (FriendsScreen.dev*), no OS input -- the click paths
		ACT_HOVER, ACT_INVITE, ACT_CHAT, ACT_MENU_ARM, ACT_ACCEPT, ACT_SEARCH_ENTER, ACT_PARTY_MENU,
		ACT_TOAST_REQUEST, ACT_TOAST_INVITE, ACT_TOAST_STALE,
		// review follow-ups: fast double-click on a confirm, per-conversation drafts, typing with no focus
		ACT_DOUBLECLICK, ACT_DRAFT, ACT_DRAFT_BACK, ACT_TYPE_SEARCH,
		// ORIGIN_SHOTS_SET=style (Button Style / Main Menu Style, 2026-10-19): Minecraft's own menus, to see
		// whether they come out Origin-styled or plain vanilla. Run once per ORIGIN_SHOTS_STYLE state.
		STY_OPTIONS, STY_VIDEO, STY_SOUND, STY_WORLDS, STY_SERVERS, STY_CREATE, STY_LOADING,
		// ORIGIN_SHOTS_SET=clean (Will's clean in-game look, 2026-10-19): the title with one button held in
		// its hover state, Controls, and the world-loading / connecting screens, on top of the STY_ menus.
		TITLE_HOVER, TITLE_HOVER_BTN, TITLE_PRESS, TITLE_FOCUS, CONTROLS, LEVEL_LOADING, CONNECTING,
		// ...and, LAST in the clean set because they create a world: the Create World screen (a fresh game dir
		// has no saves, so Singleplayer goes straight there), the pause menu inside that new world, then the
		// world list with that save in it after Save and Quit.
		CREATE_TABS, WORLD_PAUSE, WORLDS_SAVED;

		Screen open() {
			// Only MENU_CLEAR shows the see-through panel; finish() restores the setting.
			if (savedBgOpacity < 0) {
				savedBgOpacity = com.origin.client.client.mods.Mods.metaNum("menuBgOpacity", 1.0);
			}
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", this == MENU_CLEAR ? 0.0 : 1.0);
			// The friends demo only on its own shots, so every other shot looks the same in both passes.
			com.origin.client.client.social.SocialDemo.clear();
			Minecraft.getInstance().gui.toastManager().clear();
			devCursor(null);   // every screen starts with the cursor and the mouse button where they were
			devPress(false);
			return switch (this) {
				case TITLE -> new TitleScreen();
				case MENU -> new OriginModMenuScreen().preview(false, false);
				case MENU_LIST -> new OriginModMenuScreen().preview(true, false);
				// A HUD mod's own page: the settings layout the redesign is judged on.
				case MOD_SETTINGS -> new OriginModMenuScreen(null, "keystrokes").preview(false, false);
				case SETTINGS -> new OriginModMenuScreen().preview(false, true);
				// The Right Shift screen (HUD editor + the Origin centre group).
				case RSHIFT -> new HudEditorScreen(true);
				// The cosmetics locker, opened from the menu's Cosmetics tab.
				case COSMETICS -> new com.origin.client.client.gui.CosmeticsScreen(null);
				case FPSCOACH -> new com.origin.client.client.gui.FpsCoachScreen(null);
				case WAYPOINTS -> new com.origin.client.client.waypoints.WaypointScreen();
				case ITEMSIZE -> new com.origin.client.client.gui.OriginItemSizeScreen();
				case ITEMCOUNTER -> new com.origin.client.client.gui.ItemCounterPickerScreen();
				case QUICKPLAY -> new com.origin.client.client.gui.QuickplayScreen();
				case SHADERS -> new com.origin.client.client.shaders.ShaderBrowserScreen(null);
				case MENU_CLEAR -> new OriginModMenuScreen().preview(false, false);
				case FRIENDS -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL);
				}
				case FRIENDS_PARTY -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY);
				}
				case SOC_CHAT -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL);
				}
				case SOC_PARTY -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY);
				}
				case SOC_HOME -> {
					com.origin.client.client.social.SocialDemo.installHome();
					yield new com.origin.client.client.gui.FriendsScreen(null);
				}
				case SOC_OFFLINE -> {
					com.origin.client.client.social.SocialDemo.installHome();
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select("e5b1d7f3a9c24e6b0d8f2a4c6e1b9d38");   // Lumen
				}
				case SOC_EMPTY -> new com.origin.client.client.gui.FriendsScreen(null);
				case SOC_SIGNEDOUT -> {
					com.origin.client.client.social.SocialDemo.status(
							com.origin.client.client.social.SocialClient.Status.SIGNED_OUT, null);
					yield new com.origin.client.client.gui.FriendsScreen(null);
				}
				case SOC_UNREACHABLE -> {
					com.origin.client.client.social.SocialDemo.status(
							com.origin.client.client.social.SocialClient.Status.UNREACHABLE, null);
					yield new com.origin.client.client.gui.FriendsScreen(null);
				}
				case SOC_STRESS_CHAT -> {
					com.origin.client.client.social.SocialDemo.installStress();
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.firstStressFriend());
				}
				case SOC_STRESS_PARTY -> {
					com.origin.client.client.social.SocialDemo.installStress();
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY);
				}
				case SOC_TOASTS -> {
					com.origin.client.client.social.SocialDemo.install(false);
					Minecraft mc = Minecraft.getInstance();
					com.origin.client.client.social.SocialToast.message(mc, com.origin.client.client.social.SocialDemo.KESTREL,
							"Kestrel", "I'm in a lobby on hypixel now, join when you're ready and bring the good pickaxe");
					com.origin.client.client.social.SocialToast.request(mc, "9c4a2e8f6b1d4c7e3a5f9b0d2e8c6a14", "Quartzling");
					com.origin.client.client.social.SocialToast.invite(mc, "1d9f5b3e7a0c4f2d8b6e4a1c9f3d7b52", "Ravenmoor");
					com.origin.client.client.social.SocialToast.warp(mc, "0b7e4d2c9a1f4e6b8c3d5a7f2e9b1c63", "Ember_Fox",
							new com.origin.client.client.social.SocialState.Warp("play.originsmp.net", com.origin.client.client.social.Social.version(),
									System.currentTimeMillis(), "0b7e4d2c9a1f4e6b8c3d5a7f2e9b1c63", "Ember_Fox"));
					yield new TitleScreen();
				}
				case SOC_RSHIFT -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new HudEditorScreen(true);
				}
				case SOC_ADD -> {
					com.origin.client.client.social.SocialDemo.installHome();
					yield new com.origin.client.client.gui.FriendsScreen(null).devSearch("Zed_Nine");
				}
				case SOC_FILTER -> {
					com.origin.client.client.social.SocialDemo.installHome();
					yield new com.origin.client.client.gui.FriendsScreen(null).devSearch("mo");
				}
				case SOC_MENU_FRIEND -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL).devMenu();
				}
				case SOC_MENU_INVITE -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY).devMenu();
				}
				case SOC_NOTICE -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL)
							.devNotice("Party invite sent to Kestrel.", false);
				}
				case ACT_HOVER, ACT_INVITE, ACT_CHAT, ACT_ACCEPT, ACT_SEARCH_ENTER, ACT_DRAFT, ACT_DRAFT_BACK, ACT_TYPE_SEARCH -> {
					com.origin.client.client.social.SocialDemo.installHome();
					yield new com.origin.client.client.gui.FriendsScreen(null);
				}
				case ACT_TOAST_STALE -> {
					com.origin.client.client.social.SocialDemo.install(false);
					Minecraft mc = Minecraft.getInstance();
					com.origin.client.client.social.SocialToast.request(mc, "00000000000000000000000000000099", "Ghost_One");
					com.origin.client.client.social.SocialToast.invite(mc, "00000000000000000000000000000098", "Ghost_Two");
					yield new TitleScreen();
				}
				case ACT_MENU_ARM, ACT_DOUBLECLICK -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL);
				}
				case ACT_PARTY_MENU -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY);
				}
				case ACT_TOAST_REQUEST, ACT_TOAST_INVITE -> {
					com.origin.client.client.social.SocialDemo.install(false);
					Minecraft mc = Minecraft.getInstance();
					com.origin.client.client.social.SocialToast.request(mc, "9c4a2e8f6b1d4c7e3a5f9b0d2e8c6a14", "Quartzling");
					com.origin.client.client.social.SocialToast.invite(mc, "1d9f5b3e7a0c4f2d8b6e4a1c9f3d7b52", "Ravenmoor");
					yield new TitleScreen();
				}
				case SOC_MENU -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new OriginModMenuScreen().preview(false, false);
				}
				case STY_OPTIONS, STY_VIDEO -> new net.minecraft.client.gui.screens.options.OptionsScreen(
						new TitleScreen(), Minecraft.getInstance().options);
				case STY_SOUND -> new net.minecraft.client.gui.screens.options.SoundOptionsScreen(
						new TitleScreen(), Minecraft.getInstance().options);
				case STY_WORLDS, STY_CREATE -> new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(new TitleScreen());
				case STY_SERVERS -> new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(new TitleScreen());
				case STY_LOADING -> new net.minecraft.client.gui.screens.GenericMessageScreen(
						net.minecraft.network.chat.Component.literal("Preparing for world creation..."));
				case TITLE_HOVER, TITLE_HOVER_BTN, TITLE_PRESS, TITLE_FOCUS -> new TitleScreen();   // the state is set by ACTS once the screen is up
				case CONTROLS -> new net.minecraft.client.gui.screens.options.controls.ControlsScreen(
						new TitleScreen(), Minecraft.getInstance().options);
				// A load tracker nothing feeds: the screen at 0%, exactly as a world starts loading
				// (26.x: (LevelLoadTracker, Reason), javap 26.1.2; isLevelReady stays false, so it never closes itself).
				case LEVEL_LOADING -> new net.minecraft.client.gui.screens.LevelLoadingScreen(
						new net.minecraft.client.multiplayer.LevelLoadTracker(),
						net.minecraft.client.gui.screens.LevelLoadingScreen.Reason.OTHER);
				case CONNECTING -> connectScreen();
				case CREATE_TABS, WORLD_PAUSE -> new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(new TitleScreen());
				case WORLDS_SAVED -> {
					Minecraft mc = Minecraft.getInstance();
					if (mc.level != null) {
						// Vanilla's Save and Quit (PauseScreen.onDisconnect): blocks until the server has saved.
						mc.level.disconnect(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
						mc.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(
								net.minecraft.network.chat.Component.translatable("menu.savingLevel")), false);
					}
					yield new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(new TitleScreen());
				}
			};
		}
	}

	/** ConnectScreen's (Screen, Component) constructor is private (javap, 26.1.2); no connection is started,
	 *  so it just shows "Connecting to the server..." -- the state a player sees first. */
	private static Screen connectScreen() {
		try {
			var c = net.minecraft.client.gui.screens.ConnectScreen.class.getDeclaredConstructor(
					Screen.class, net.minecraft.network.chat.Component.class);
			c.setAccessible(true);
			return c.newInstance(new TitleScreen(), net.minecraft.network.chat.CommonComponents.CONNECT_FAILED);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("ConnectScreen constructor", e);
		}
	}

	private record Shot(int w, int h, int scale, Kind kind) {
		String name() {
			return kind.name().toLowerCase(java.util.Locale.ROOT) + "_" + w + "x" + h + "_s" + scale + ".png";
		}
	}

	private static final List<Shot> SHOTS = new ArrayList<>();

	/** Scripted steps per ACT_ kind, run one per ~600 ms after the screen is up, then the capture. */
	private static final java.util.Map<Kind, List<java.util.function.Consumer<Screen>>> ACTS = new java.util.EnumMap<>(Kind.class);
	private static int actIdx = 0;

	/** Presses the button on {@code s} whose label is {@code label} (the game's own translation, so any language). */
	private static void pressLabel(Screen s, String label) {
		if (s == null) {
			return;
		}
		for (var child : s.children()) {
			if (child instanceof net.minecraft.client.gui.components.AbstractButton b && b.active
					&& label.equals(b.getMessage().getString())) {
				b.onPress(new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
				return;
			}
		}
		com.origin.client.OriginClient.LOGGER.warn("[dev-shots] no button labelled '{}' on {}", label, s.getClass().getSimpleName());
	}

	private static com.origin.client.client.gui.FriendsScreen fs(Screen s) {
		return (com.origin.client.client.gui.FriendsScreen) s;
	}

	static {
		// design-px coordinates of the 1080p layout (panel origin 500,250 on screen): see FriendsScreen's layout constants
		// Title states (the cursor/press/focus placed on the live widgets once the title is laid out).
		ACTS.put(Kind.TITLE_HOVER, List.of(s -> devCursor(titleDockLast(s))));
		ACTS.put(Kind.TITLE_HOVER_BTN, List.of(s -> devCursor(titleButton(s, "menu.multiplayer", null))));
		ACTS.put(Kind.TITLE_PRESS, List.of(s -> {
			devCursor(titleButton(s, "options.title", null));
			devPress(true);
		}));
		ACTS.put(Kind.TITLE_FOCUS, List.of(s -> devFocus(s, titleButton(s, "menu.singleplayer", null))));
		ACTS.put(Kind.ACT_HOVER, List.of(s -> fs(s).devHover(180, 385)));
		ACTS.put(Kind.ACT_INVITE, List.of(s -> fs(s).devHover(180, 385), s -> fs(s).devClick(248, 385)));
		ACTS.put(Kind.ACT_ACCEPT, List.of(s -> fs(s).devHover(228, 233), s -> fs(s).devClick(228, 233)));
		ACTS.put(Kind.ACT_CHAT, List.of(s -> fs(s).devClick(140, 385),
				s -> fs(s).devChars("hello there, ready for the stronghold? I have the good pickaxe and 12 eyes of ender"),
				s -> fs(s).devKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN)));
		ACTS.put(Kind.ACT_MENU_ARM, List.of(s -> fs(s).devClick(880, 158), s -> fs(s).devHover(792, 200),
				s -> fs(s).devClick(792, 200)));
		ACTS.put(Kind.ACT_SEARCH_ENTER, List.of(s -> fs(s).devChars("zed_nine"), s -> fs(s).devKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN)));
		ACTS.put(Kind.ACT_PARTY_MENU, List.of(s -> fs(s).devClick(760, 218)));
		ACTS.put(Kind.ACT_DOUBLECLICK, List.of(s -> fs(s).devClick(880, 158), s -> {
			fs(s).devClick(792, 200);   // arms
			fs(s).devClick(792, 200);   // a second click inside 350 ms must be ignored
		}));
		ACTS.put(Kind.ACT_DRAFT, List.of(s -> fs(s).devClick(140, 385), s -> fs(s).devChars("draft for ember only"),
				s -> fs(s).devClick(140, 431)));
		ACTS.put(Kind.ACT_DRAFT_BACK, List.of(s -> fs(s).devClick(140, 385), s -> fs(s).devChars("draft for ember only"),
				s -> fs(s).devClick(140, 431), s -> fs(s).devClick(140, 385)));
		ACTS.put(Kind.ACT_TYPE_SEARCH, List.of(s -> fs(s).devClick(140, 385), s -> fs(s).devClick(150, 106),
				s -> fs(s).devClick(400, 40), s -> fs(s).devChars("ke")));
		// Style set: open Video Settings (Sodium's, through the real button) / Create New World (its tabs).
		ACTS.put(Kind.STY_VIDEO, List.of(s -> pressLabel(s, net.minecraft.client.resources.language.I18n.get("options.video")), s -> {
		}));
		ACTS.put(Kind.STY_CREATE, List.of(s -> pressLabel(s, net.minecraft.client.resources.language.I18n.get("selectWorld.create")), s -> {
		}, s -> {
		}));
		// Clean set: let the Create World screen finish loading its data packs (a fresh game dir redirects
		// Singleplayer there), then shoot it. WORLD_PAUSE also presses Create (only on that screen, never
		// on a world list), waits out the world load (~20 s on CI's software GL) and opens the pause menu.
		java.util.function.Consumer<Screen> idle = s -> {
		};
		ACTS.put(Kind.CREATE_TABS, List.of(idle, idle, idle, idle));
		List<java.util.function.Consumer<Screen>> enter = new ArrayList<>(List.of(idle, idle, idle, idle, s -> {
			if (s instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen) {
				pressLabel(s, net.minecraft.client.resources.language.I18n.get("selectWorld.create"));
			}
		}));
		for (int i = 0; i < 48; i++) {
			enter.add(idle);
		}
		enter.add(s -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null && !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)) {
				mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
			}
		});
		enter.add(idle);
		ACTS.put(Kind.WORLD_PAUSE, enter);
		ACTS.put(Kind.WORLDS_SAVED, List.of(idle, idle));
		ACTS.put(Kind.ACT_TOAST_STALE, List.of(s -> {
			com.origin.client.client.social.SocialToast.devClickButton("req:00000000000000000000000000000099");
			com.origin.client.client.social.SocialToast.devClickButton("inv:00000000000000000000000000000098");
		}));
		ACTS.put(Kind.ACT_TOAST_REQUEST, List.of(s -> com.origin.client.client.social.SocialToast.devClickButton("req:9c4a2e8f6b1d4c7e3a5f9b0d2e8c6a14")));
		ACTS.put(Kind.ACT_TOAST_INVITE, List.of(s -> com.origin.client.client.social.SocialToast.devClickButton("inv:1d9f5b3e7a0c4f2d8b6e4a1c9f3d7b52")));
	}
	private static double savedBgOpacity = -1;
	/** ORIGIN_SHOTS_SET=world: OriginDevWorld captures inside a fresh world instead. */
	private static final boolean WORLD = "world".equals(System.getenv("ORIGIN_SHOTS_SET"));
	static {
		if (WORLD) {
			// no screen matrix: OriginDevWorld takes over once the title is up
		} else if ("style".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// Button Style / Main Menu Style: the title, the Origin windows (which must stay Origin in every
			// state) and Minecraft's own menus, at GUI scale 2 on 1080p. The pause menu and the startup loading
			// screen are caught by the world set / the boot shot below.
			Kind[] ks = {Kind.TITLE, Kind.MENU, Kind.SETTINGS, Kind.RSHIFT, Kind.STY_OPTIONS, Kind.STY_VIDEO,
					Kind.STY_SOUND, Kind.STY_WORLDS, Kind.STY_CREATE, Kind.STY_SERVERS, Kind.STY_LOADING};
			for (Kind k : ks) {
				SHOTS.add(new Shot(1920, 1080, 2, k));
			}
		} else if ("clean".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// The clean in-game look: Minecraft's own menus + loading screens at GUI scale 2 on 1080p, the
			// Origin mod menu (must be unchanged), and the title at 720p and GUI scale 3 / 4 (clipping checks).
			// The startup loading screen comes from the boot shot below.
			Kind[] ks = {Kind.TITLE, Kind.TITLE_HOVER, Kind.STY_OPTIONS, Kind.STY_VIDEO, Kind.CONTROLS,
					Kind.STY_SOUND, Kind.STY_SERVERS, Kind.STY_LOADING, Kind.LEVEL_LOADING, Kind.CONNECTING, Kind.MENU};
			for (Kind k : ks) {
				SHOTS.add(new Shot(1920, 1080, 2, k));
			}
			SHOTS.add(new Shot(1280, 720, 2, Kind.TITLE));
			SHOTS.add(new Shot(1920, 1080, 3, Kind.TITLE));
			SHOTS.add(new Shot(1920, 1080, 4, Kind.TITLE));
			SHOTS.add(new Shot(1920, 1080, 3, Kind.STY_OPTIONS));
			// These create a world, so they go last (everything above must be shot out of world).
			SHOTS.add(new Shot(1920, 1080, 2, Kind.CREATE_TABS));
			SHOTS.add(new Shot(1920, 1080, 2, Kind.WORLD_PAUSE));
			SHOTS.add(new Shot(1920, 1080, 2, Kind.WORLDS_SAVED));
		} else if ("social-act".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			for (int sc : new int[]{2, 4}) {
				for (Kind k : Kind.values()) {
					if (k.name().startsWith("ACT_")) {
						SHOTS.add(new Shot(1920, 1080, sc, k));
					}
				}
			}
		} else if ("social".equals(System.getenv("ORIGIN_SHOTS_SET"))
				|| "social-failreload".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// Friends / party / chat / toasts: 1080p at GUI scale 2, 3 and 4 (the contract), plus 720p at 2.
			for (int[] s : new int[][]{{1920, 1080, 2}, {1920, 1080, 4}, {1920, 1080, 3}, {1280, 720, 2}}) {
				for (Kind k : Kind.values()) {
					if (k.name().startsWith("SOC_")) {
						SHOTS.add(new Shot(s[0], s[1], s[2], k));
					}
				}
			}
		} else if ("handoff".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// The loading -> title hand-off measurement (OriginFrameLog): one title
			// shot, taken only once the frame log has printed, then quit.
			SHOTS.add(new Shot(1920, 1080, 0, Kind.TITLE));
		} else if ("design".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// Design review (the menu / HUD-editor redesign loop): every screen at
			// the two most common desktop sizes, auto GUI scale.
			for (int[] s : new int[][]{{1920, 1080}, {1366, 768}}) {
				for (Kind k : Kind.values()) {
					if (!k.name().startsWith("STY_")) {
						SHOTS.add(new Shot(s[0], s[1], 0, k));
					}
				}
			}
			// Same menu at a forced GUI scale: the redesign must not change size with it.
			SHOTS.add(new Shot(1920, 1080, 2, Kind.MENU));
		} else {
			// Common desktop ratios at auto GUI scale (0 = Minecraft's own choice),
			// then the two extremes of GUI scale at 1080p.
			int[][] sizes = {{1920, 1080}, {2560, 1080}, {1280, 720}, {1024, 768}, {1366, 768}, {1600, 900}};
			for (int[] s : sizes) {
				SHOTS.add(new Shot(s[0], s[1], 0, Kind.TITLE));
				SHOTS.add(new Shot(s[0], s[1], 0, Kind.MENU));
			}
			for (int sc : new int[]{2, 4}) {
				SHOTS.add(new Shot(1920, 1080, sc, Kind.TITLE));
				SHOTS.add(new Shot(1920, 1080, sc, Kind.MENU));
			}
		}
	}

	private static int step = -1;      // -1 = waiting for the title to be up
	private static int phase = 0;      // 0 = resize, 1 = open screen, 2 = grab
	private static long nextAt = 0;
	private static int savedScale = -1;
	private static int resizeRetries = 0;
	private static boolean done = false;
	// phase 3: the PNGs that existed before the grab, and how long we've waited
	private static Set<String> before = new HashSet<>();
	private static int renameRetries = 0;

	private OriginDevShots() {
	}

	public static boolean enabled() {
		return DIR != null && !DIR.isEmpty();
	}

	/**
	 * ORIGIN_SHOTS_STYLE (dev-only, only with ORIGIN_SHOTS set): forces the two style options for the whole
	 * run, from the moment the mod starts so the startup loading screen is covered too:
	 * "buttons" = Button Style Minecraft; "menu" = Main Menu Style Vanilla; "vanilla" = both. Unset/empty = the
	 * player's own settings (Origin by default). The real options are written (so the mod menu shows what is
	 * forced) and put back when the shot matrix finishes. Called from OriginClientMod.onInitializeClient.
	 */
	public static void applyStyleOverride() {
		String style = System.getenv("ORIGIN_SHOTS_STYLE");
		if (!enabled() || style == null || style.isEmpty()) {
			return;
		}
		try {
			String g = com.origin.client.client.mods.Mods.GENERAL_ID;
			// No Button Style any more (Minecraft's menus are always stock widgets).
			savedStyle = new String[]{null, com.origin.client.client.mods.Mods.mode(g, "mainMenuStyle")};
			if (style.equals("menu") || style.equals("vanilla")) {
				com.origin.client.client.mods.Mods.set(g, "mainMenuStyle", "Vanilla");
			}
			com.origin.client.OriginClient.LOGGER.info("[dev-shots] style override '{}' (was menu={})",
					style, savedStyle[1]);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] style override failed", t);
		}
	}

	// Title state shots (TITLE_HOVER / _HOVER_BTN / _PRESS / _FOCUS). Xvfb has no pointer moving,
	// so the position written into MouseHandler stays put. The cursor has to be placed AFTER the
	// title screen exists and has laid out (an ACTS step, ~1.9 s after open): placing it while
	// opening read the PREVIOUS screen's widgets, so it hovered nothing (found on 1.20, 2026-10-25).
	// Every open() restores the original cursor and button state.
	// Unobfuscated, so the reflected field names hold in production too.
	private static double[] savedCursor;
	private static java.lang.reflect.Field tooltipField;

	/** 26.x has no AbstractWidget.getTooltip(): read its WidgetTooltipHolder (unobfuscated, so the name holds). */
	private static boolean hasTooltip(net.minecraft.client.gui.components.AbstractWidget w) {
		try {
			if (tooltipField == null) {
				tooltipField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("tooltip");
				tooltipField.setAccessible(true);
			}
			Object h = tooltipField.get(w);
			return h instanceof net.minecraft.client.gui.components.WidgetTooltipHolder t && t.get() != null;
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	/** The title widget labelled with the given translation key (the game's own text), or by its literal label. */
	private static net.minecraft.client.gui.components.AbstractWidget titleButton(Screen s, String key, String literal) {
		String want = key != null ? net.minecraft.client.resources.language.I18n.get(key) : literal;
		for (var child : s.children()) {
			if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible
					&& want.equals(w.getMessage().getString())) {
				return w;
			}
		}
		return null;
	}

	/** The dock's last glyph with a tooltip (Quit): the right-most visible widget on the dock row. */
	private static net.minecraft.client.gui.components.AbstractWidget titleDockLast(Screen s) {
		var win = Minecraft.getInstance().getWindow();
		com.origin.client.client.render.TitleLayout L = com.origin.client.client.render.TitleLayout.of(
				win.getGuiScaledWidth(), win.getGuiScaledHeight());
		net.minecraft.client.gui.components.AbstractWidget last = null;
		for (var child : s.children()) {
			if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible
					&& w.getY() >= L.dockY && hasTooltip(w)
					&& (last == null || w.getX() > last.getX())) {
				last = w;
			}
		}
		return last;
	}

	/** Parks the cursor on the centre of {@code target}; null puts it back where it was. */
	private static void devCursor(net.minecraft.client.gui.components.AbstractWidget target) {
		try {
			Minecraft mc = Minecraft.getInstance();
			java.lang.reflect.Field fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
			java.lang.reflect.Field fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
			fx.setAccessible(true);
			fy.setAccessible(true);
			if (savedCursor == null) {
				savedCursor = new double[]{fx.getDouble(mc.mouseHandler), fy.getDouble(mc.mouseHandler)};
			}
			double x = savedCursor[0], y = savedCursor[1];
			if (target != null) {
				double gs = mc.getWindow().getGuiScale();
				x = (target.getX() + target.getWidth() / 2.0) * gs;
				y = (target.getY() + target.getHeight() / 2.0) * gs;
			}
			fx.setDouble(mc.mouseHandler, x);
			fy.setDouble(mc.mouseHandler, y);
			if (target != null) {
				com.origin.client.OriginClient.LOGGER.info("[dev-shots] cursor on '{}' at {},{} (hovered next frame)",
						target.getMessage().getString(), (int) x, (int) y);
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] cursor placement failed", t);
		}
	}

	/** Holds (or releases) the left mouse button as far as Origin's own press look can tell (MouseHandler.isLeftPressed). */
	private static void devPress(boolean down) {
		try {
			java.lang.reflect.Field f = net.minecraft.client.MouseHandler.class.getDeclaredField("isLeftPressed");
			f.setAccessible(true);
			f.setBoolean(Minecraft.getInstance().mouseHandler, down);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] press placement failed", t);
		}
	}

	/** Keyboard focus on {@code w} (the focus ring state). */
	private static void devFocus(Screen s, net.minecraft.client.gui.components.AbstractWidget w) {
		if (w != null) {
			s.setFocused(w);
			com.origin.client.OriginClient.LOGGER.info("[dev-shots] focus on '{}'", w.getMessage().getString());
		} else {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] focus target not found on {}", s.getClass().getSimpleName());
		}
	}

	private static String[] savedStyle;
	private static boolean bootShot = false;
	private static int overlayTicks = 0;

	/** Style set only: one frame of the STARTUP loading screen (the overlay only exists while the game boots). */
	private static void bootLoadingShot(Minecraft mc) {
		String set = System.getenv("ORIGIN_SHOTS_SET");
		if (bootShot || !("style".equals(set) || "clean".equals(set)) || mc.gui.overlay() == null) {
			return;
		}
		if (++overlayTicks >= 12) {
			bootShot = true;
			// timestamp-named in screenshots/ (this era writes the PNG on the IO thread)
			Screenshot.grab(new File(DIR), mc.gameRenderer.mainRenderTarget(), c -> {
			});
			com.origin.client.OriginClient.LOGGER.info("[dev-shots] captured the startup loading screen");
		}
	}

	public static void tick(Minecraft mc) {
		if (done) {
			return;
		}
		bootLoadingShot(mc);
		long now = System.currentTimeMillis();
		if (step < 0) {
			// A fresh game directory (CI) opens vanilla's one-time accessibility
			// onboarding screen instead of the title; skip it, or the harness would
			// wait for a title screen that never comes.
			if (mc.gui.overlay() == null && mc.gui.screen() != null && !(mc.gui.screen() instanceof TitleScreen)
					&& !(mc.gui.screen() instanceof OriginModMenuScreen)) {
				mc.options.onboardAccessibility = false;
				mc.gui.setScreen(new TitleScreen());
				return;
			}
			if (mc.gui.overlay() == null && mc.gui.screen() instanceof TitleScreen) {
				if (nextAt == 0) {
					nextAt = now + 1500;
				} else if (now >= nextAt && OriginFrameLog.done()) {   // never resize under the hand-off being measured
					File output = new File(DIR);
					if (!output.isDirectory() && !output.mkdirs()) {
						com.origin.client.OriginClient.LOGGER.error(
								"[dev-shots] could not create output directory {}", output.getAbsolutePath());
						done = true;
						mc.stop();
						return;
					}
					savedScale = mc.options.guiScale().get();
					step = 0;
					phase = 0;
					nextAt = 0;
				}
			}
			return;
		}
		if (WORLD) {
			OriginDevWorld.tick(mc, DIR);
			return;
		}
		if (now < nextAt) {
			return;
		}
		if (step >= SHOTS.size()) {
			finish(mc);
			return;
		}
		Shot s = SHOTS.get(step);
		try {
			switch (phase) {
				case 0 -> {
					SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(), s.w(), s.h());
					mc.options.guiScale().set(s.scale());
					mc.framebufferSizeChanged();
					resizeRetries = 0;
					phase = 1;
					nextAt = now + 800;
				}
				case 1 -> {
					// Fresh screens so entrance animations replay and get captured settled.
					mc.gui.setScreen(s.kind().open());
					mc.framebufferSizeChanged();
					// 26.3: Gui.setScreen resyncs the cursor from SDL's real mouse state
					// (MouseHandler.resyncMousePosition). The title-state cursor/press/focus are
					// placed by the ACTS steps below, after this resync, so they stick.
					actIdx = 0;
					phase = 2;
					// The shader browser's first open starts its preview downloads; under
					// software GL (CI) that first second is slow, so it gets longer to settle.
					nextAt = now + (s.kind() == Kind.TITLE ? 1900 : s.kind() == Kind.SHADERS ? 3000 : 900);
				}
				case 2 -> {
					List<java.util.function.Consumer<Screen>> acts = ACTS.get(s.kind());
					if (acts != null && actIdx < acts.size()) {
						acts.get(actIdx++).accept(mc.gui.screen());
						nextAt = now + 700;
						return;
					}
					if (s.kind() == Kind.SHADERS && waitForShaderPreviews(now)) {
						return;
					}
					// Windows may apply a resize one message-loop later (especially
					// immediately after changing GUI scale). Never label a capture with
					// dimensions its framebuffer did not actually reach.
					int actualW = mc.getWindow().getWidth();
					int actualH = mc.getWindow().getHeight();
					if ((actualW != s.w() || actualH != s.h()) && resizeRetries++ < 8) {
						SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(), s.w(), s.h());
						mc.framebufferSizeChanged();
						nextAt = now + 350;
						return;
					}
					if (actualW != s.w() || actualH != s.h()) {
						throw new IllegalStateException("window stayed at " + actualW + "x" + actualH
								+ " instead of " + s.w() + "x" + s.h());
					}
					before = pngs();
					renameRetries = 0;
					Screenshot.grab(new File(DIR), mc.gameRenderer.mainRenderTarget(), c -> {
					});
					phase = 3;
					nextAt = now + 250;
				}
				case 3 -> {
					// The PNG is written on the IO thread: wait for the new file, then
					// give it the shot's name.
					File dir = new File(DIR, "screenshots");
					String fresh = null;
					for (String n : pngs()) {
						if (!before.contains(n)) {
							fresh = n;
						}
					}
					if (fresh == null && renameRetries++ < 20) {
						nextAt = now + 250;
						return;
					}
					if (fresh == null) {
						throw new IllegalStateException("no screenshot appeared for " + s.name());
					}
					Files.move(new File(dir, fresh).toPath(), new File(dir, s.name()).toPath(),
							StandardCopyOption.REPLACE_EXISTING);
					com.origin.client.OriginClient.LOGGER.info("[dev-shots] captured {}", s.name());
					step++;
					phase = 0;
					nextAt = now + 250;
				}
				default -> phase = 0;
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] step {} failed", step, t);
			step++;
			phase = 0;
			nextAt = now + 250;
		}
	}

	/** PNG names currently in the output's screenshots/ folder (OriginDevWorld uses it too). */
	static Set<String> pngs() {
		Set<String> out = new HashSet<>();
		String[] names = new File(DIR, "screenshots").list();
		if (names != null) {
			for (String n : names) {
				if (n.endsWith(".png")) {
					out.add(n);
				}
			}
		}
		return out;
	}

	// The shader browser's thumbnails load async (Modrinth fetch + WebP decode) and a
	// run's first open starts them, so the first capture could show placeholder tiles
	// while the same session's second capture (a cache hit) showed the images. Two
	// races: the loads can outlast the screen's 900 ms settle (Java 8/16/17 modules
	// under CI's software GL), and the last upload + LOADING decrement run in the
	// frame-start task batch AHEAD of this client tick, so loading() can read false
	// while the framebuffer the grab reads was drawn before the textures existed.
	// So: wait for the loads (max 20 s), then ALWAYS settle once with loading() false,
	// which guarantees at least one frame rendered after the last upload.
	private static long previewWaitFrom = 0;   // 0 = not waiting
	private static boolean previewSettled = false;

	private static boolean waitForShaderPreviews(long now) {
		boolean loading = com.origin.client.client.shaders.ShaderPreviews.loading();
		if (previewWaitFrom == 0) {
			previewWaitFrom = now;
		}
		if (loading && now - previewWaitFrom < 20000) {
			previewSettled = false;
			nextAt = now + 250;
			return true;
		}
		if (!previewSettled) {
			previewSettled = true;
			nextAt = now + 500;
			return true;
		}
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] waited {} ms for shader previews{}",
				now - previewWaitFrom, loading ? " (gave up, still loading)" : "");
		previewWaitFrom = 0;
		previewSettled = false;
		return false;
	}

	private static void finish(Minecraft mc) {
		done = true;
		if (savedStyle != null) {
			try {
				com.origin.client.client.mods.Mods.set(com.origin.client.client.mods.Mods.GENERAL_ID, "mainMenuStyle", savedStyle[1]);
			} catch (Throwable ignored) {
			}
		}
		if (savedBgOpacity >= 0) {
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", savedBgOpacity);
		}
		try {
			if (savedScale >= 0) {
				mc.options.guiScale().set(savedScale);
			}
		} catch (Throwable ignored) {
		}
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — {} shots in {}", SHOTS.size(), DIR);
		mc.stop();
	}
}
