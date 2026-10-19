package com.origin.client.client;

import com.origin.client.client.gui.OriginModMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import com.origin.client.client.hud.HudEditorScreen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * DEV-ONLY visual verification harness (2026-09). Off unless the environment
 * variable {@code ORIGIN_SHOTS} names a directory. When it does, the client
 * boots to the title screen, then walks a matrix of window sizes × GUI scales,
 * capturing the TITLE (after its entrance animation) and the MOD MENU at each,
 * into {@code $ORIGIN_SHOTS/screenshots/}, and finally quits. With
 * {@code ORIGIN_SHOTS_SET=design} it instead captures the title, the mod menu
 * (grid + list + see-through), a mod's settings page, the Settings tab, the
 * Right Shift screen and every Origin window at two sizes; with
 * {@code ORIGIN_SHOTS_SET=world} OriginDevWorld captures inside a fresh world
 * (.github/workflows/dev-shots.yml). This is how a
 * layout change gets LOOKED AT on every aspect ratio before it reaches Will —
 * no more "compiles, ships, looks wrong".
 *
 * Runs on the client tick (render thread). Each step waits long enough for
 * the window to actually resize and for entrance animations to settle.
 * Never touches config; the GUI-scale option is restored at the end.
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
		// ORIGIN_SHOTS_SET=perf: the real Sodium / Iris screens (opened by reflection, see perfScreen)
		PERF_SODIUM, PERF_IRIS;

		Screen open() {
			// Only MENU_CLEAR shows the see-through panel; finish() restores the setting.
			if (savedBgOpacity < 0) {
				savedBgOpacity = com.origin.client.client.mods.Mods.metaNum("menuBgOpacity", 1.0);
			}
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", this == MENU_CLEAR ? 0.0 : 1.0);
			// The friends demo only on its own shots, so every other shot looks the same in both passes.
			com.origin.client.client.social.SocialDemo.clear();
			Minecraft.getInstance().getToasts().clear();
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
							new com.origin.client.client.social.SocialState.Warp("play.originsmp.net", "1.21.1",
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
				case PERF_SODIUM -> perfScreen("net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI", "me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI");
				case PERF_IRIS -> perfScreen("net.irisshaders.iris.gui.screen.ShaderPackScreen", "net.coderbot.iris.gui.screen.ShaderPackScreen");
				case STY_OPTIONS, STY_VIDEO -> new net.minecraft.client.gui.screens.OptionsScreen(
						new TitleScreen(), Minecraft.getInstance().options);
				case STY_SOUND -> new net.minecraft.client.gui.screens.SoundOptionsScreen(
						new TitleScreen(), Minecraft.getInstance().options);
				case STY_WORLDS, STY_CREATE -> new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(new TitleScreen());
				case STY_SERVERS -> new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(new TitleScreen());
				case STY_LOADING -> new net.minecraft.client.gui.screens.GenericDirtMessageScreen(
						new net.minecraft.network.chat.TextComponent("Preparing for world creation..."));
			};
		}
	}

	private record Shot(int w, int h, int scale, Kind kind) {
		String name() {
			return kind.name().toLowerCase(java.util.Locale.ROOT) + "_" + w + "x" + h + "_s" + scale + ".png";
		}
	}

	/**
	 * ORIGIN_SHOTS_SET=perf: opens a Sodium / Iris screen by reflection (their package roots differ per
	 * era: me.jellysquid / net.caffeinemc, net.coderbot / net.irisshaders). Throws when none is loaded, so a
	 * run without the real Sodium/Iris jars loses the shot and tools/ci/check_shots.py fails the job instead
	 * of capturing something else.
	 */
	private static Screen perfScreen(String... classNames) {
		for (String cn : classNames) {
			try {
				Class<?> c = Class.forName(cn, true, OriginDevShots.class.getClassLoader());
				Screen s;
				try {
					s = (Screen) c.getConstructor(Screen.class).newInstance(new TitleScreen());
				} catch (NoSuchMethodException noCtor) {
					// Sodium 0.6+ builds its options screen through a static factory instead
					s = (Screen) c.getMethod("createScreen", Screen.class).invoke(null, new TitleScreen());
				}
				com.origin.client.OriginClient.LOGGER.info("[dev-shots] perf screen opened: {}", cn);
				return s;
			} catch (ClassNotFoundException | NoClassDefFoundError ignored) {
				// not this era's package root
			} catch (Throwable t) {
				com.origin.client.OriginClient.LOGGER.warn("[dev-shots] perf screen {} failed to open", cn, t);
			}
		}
		throw new IllegalStateException("none of " + java.util.Arrays.toString(classNames) + " could be opened (Sodium/Iris not loaded?)");
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
				b.onPress();
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
		ACTS.put(Kind.ACT_HOVER, List.of(s -> fs(s).devHover(180, 385)));
		ACTS.put(Kind.ACT_INVITE, List.of(s -> fs(s).devHover(180, 385), s -> fs(s).devClick(248, 385)));
		ACTS.put(Kind.ACT_ACCEPT, List.of(s -> fs(s).devHover(228, 233), s -> fs(s).devClick(228, 233)));
		ACTS.put(Kind.ACT_CHAT, List.of(s -> fs(s).devClick(140, 385),
				s -> fs(s).devChars("hello there, ready for the stronghold? I have the good pickaxe and 12 eyes of ender"),
				s -> fs(s).devKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER)));
		ACTS.put(Kind.ACT_MENU_ARM, List.of(s -> fs(s).devClick(880, 158), s -> fs(s).devHover(792, 200),
				s -> fs(s).devClick(792, 200)));
		ACTS.put(Kind.ACT_SEARCH_ENTER, List.of(s -> fs(s).devChars("zed_nine"), s -> fs(s).devKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER)));
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
		// Style set: open Video Settings / Create New World (its tabs) through the real buttons.
		ACTS.put(Kind.STY_VIDEO, List.of(s -> pressLabel(s, net.minecraft.client.resources.language.I18n.get("options.video")), s -> {
		}));
		ACTS.put(Kind.STY_CREATE, List.of(s -> pressLabel(s, net.minecraft.client.resources.language.I18n.get("selectWorld.create")), s -> {
		}, s -> {
		}));
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
			// state) and Minecraft's own menus, at GUI scale 2 on 1080p.
			Kind[] ks = {Kind.TITLE, Kind.MENU, Kind.SETTINGS, Kind.RSHIFT, Kind.STY_OPTIONS, Kind.STY_VIDEO,
					Kind.STY_SOUND, Kind.STY_WORLDS, Kind.STY_CREATE, Kind.STY_SERVERS, Kind.STY_LOADING};
			for (Kind k : ks) {
				SHOTS.add(new Shot(1920, 1080, 2, k));
			}
		} else if ("social-act".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			for (int sc : new int[]{2, 4}) {
				for (Kind k : Kind.values()) {
					if (k.name().startsWith("ACT_")) {
						SHOTS.add(new Shot(1920, 1080, sc, k));
					}
				}
			}
		} else if ("social".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
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
		} else if ("perf".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// Sodium's Video Settings (with Origin's mod rail) and Iris's shader-pack screen, from the REAL
			// jars dev-shots.yml fetches into libs/ (tools/ci/perf_libs.py). 1080p, GUI scale 2.
			SHOTS.add(new Shot(1920, 1080, 2, Kind.TITLE));
			SHOTS.add(new Shot(1920, 1080, 2, Kind.PERF_SODIUM));
			SHOTS.add(new Shot(1920, 1080, 2, Kind.PERF_IRIS));
		} else if ("design".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// Design review (the menu / HUD-editor redesign loop): every screen at
			// the two most common desktop sizes, auto GUI scale.
			for (int[] s : new int[][]{{1920, 1080}, {1366, 768}}) {
				for (Kind k : Kind.values()) {
					if (!k.name().startsWith("STY_") && !k.name().startsWith("PERF_")) {
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

	private OriginDevShots() {
	}

	public static boolean enabled() {
		return DIR != null && !DIR.isEmpty();
	}

	/**
	 * ORIGIN_SHOTS_STYLE (dev-only, only with ORIGIN_SHOTS set): forces the two style options for the whole
	 * run: "buttons" = Button Style Minecraft; "menu" = Main Menu Style Vanilla; "vanilla" = both. Unset/empty =
	 * the player's own settings. The real options are written and put back when the shot matrix finishes.
	 * Called from OriginClientMod.onInitializeClient.
	 */
	public static void applyStyleOverride() {
		String style = System.getenv("ORIGIN_SHOTS_STYLE");
		if (!enabled() || style == null || style.isEmpty()) {
			return;
		}
		try {
			String g = com.origin.client.client.mods.Mods.GENERAL_ID;
			savedStyle = new String[]{com.origin.client.client.mods.Mods.mode(g, "buttonStyle"),
					com.origin.client.client.mods.Mods.mode(g, "mainMenuStyle")};
			if (style.equals("buttons") || style.equals("vanilla")) {
				com.origin.client.client.mods.Mods.set(g, "buttonStyle", "Minecraft");
			}
			if (style.equals("menu") || style.equals("vanilla")) {
				com.origin.client.client.mods.Mods.set(g, "mainMenuStyle", "Vanilla");
			}
			com.origin.client.OriginClient.LOGGER.info("[dev-shots] style override '{}' (was buttons={}, menu={})",
					style, savedStyle[0], savedStyle[1]);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] style override failed", t);
		}
	}

	private static String[] savedStyle;

	public static void tick(Minecraft mc) {
		if (done) {
			return;
		}
		long now = System.currentTimeMillis();
		if (step < 0) {
			if (mc.getOverlay() == null && mc.screen instanceof TitleScreen) {
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
					savedScale = mc.options.guiScale;   // pre-1.19: a plain field (no OptionInstance)
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
					mc.getWindow().setWindowed(s.w(), s.h());
					mc.options.guiScale = s.scale();
					mc.resizeDisplay();
					resizeRetries = 0;
					phase = 1;
					nextAt = now + 800;
				}
				case 1 -> {
					// Fresh screens so entrance animations replay and get captured settled.
					mc.setScreen(s.kind().open());
					mc.resizeDisplay();
					actIdx = 0;
					phase = 2;
					nextAt = now + (s.kind() == Kind.TITLE ? 1900 : 900);
				}
				case 2 -> {
					List<java.util.function.Consumer<Screen>> acts = ACTS.get(s.kind());
					if (acts != null && actIdx < acts.size()) {
						acts.get(actIdx++).accept(mc.screen);
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
						mc.getWindow().setWindowed(s.w(), s.h());
						mc.resizeDisplay();
						nextAt = now + 350;
						return;
					}
					if (actualW != s.w() || actualH != s.h()) {
						throw new IllegalStateException("window stayed at " + actualW + "x" + actualH
								+ " instead of " + s.w() + "x" + s.h());
					}
					Screenshot.grab(new File(DIR), s.name(), mc.getMainRenderTarget(), c -> {
					});
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
				com.origin.client.client.mods.Mods.set(com.origin.client.client.mods.Mods.GENERAL_ID, "buttonStyle", savedStyle[0]);
				com.origin.client.client.mods.Mods.set(com.origin.client.client.mods.Mods.GENERAL_ID, "mainMenuStyle", savedStyle[1]);
			} catch (Throwable ignored) {
			}
		}
		if (savedBgOpacity >= 0) {
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", savedBgOpacity);
		}
		try {
			if (savedScale >= 0) {
				mc.options.guiScale = savedScale;
			}
		} catch (Throwable ignored) {
		}
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — {} shots in {}", SHOTS.size(), DIR);
		mc.stop();
	}
}
