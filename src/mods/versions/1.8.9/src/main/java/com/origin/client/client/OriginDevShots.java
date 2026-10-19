package com.origin.client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
 * (.github/workflows/dev-shots.yml). This is how a layout change gets LOOKED
 * AT on every aspect ratio before it reaches Will.
 *
 * Runs on the client tick (render thread). Each step waits long enough for
 * the window to actually resize and for entrance animations to settle.
 * Never touches config; the GUI-scale option is restored at the end.
 *
 * 1.8.9: the same shot list and file names as 1.16.5. The window is resized
 * with LWJGL 2's Display.setDisplayMode + Minecraft.resize (no GLFW); the
 * capture is ScreenShotHelper.saveScreenshot(dir, name, w, h, framebuffer),
 * which writes to dir/screenshots/name like 1.16.5's Screenshot.grab. The
 * screens other areas own are built by class name (reflection), so the
 * harness compiles and runs before every area has landed -- a missing screen
 * is a skipped shot with a log line, never a failed run.
 */
public final class OriginDevShots {
	private static final String DIR = System.getenv("ORIGIN_SHOTS");
	private static final String P = "com.origin.client.client.";

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
		STY_OPTIONS, STY_VIDEO, STY_SOUND, STY_WORLDS, STY_SERVERS, STY_CREATE, STY_LOADING;

		GuiScreen open() {
			// Only MENU_CLEAR shows the see-through panel; finish() restores the setting.
			if (savedBgOpacity < 0) {
				savedBgOpacity = com.origin.client.client.mods.Mods.metaNum("menuBgOpacity", 1.0);
			}
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", this == MENU_CLEAR ? 0.0 : 1.0);
			// The friends demo only on its own shots, so every other shot looks the same in both passes.
			callStatic(P + "social.SocialDemo", "clear");
			// 1.21.1 clears vanilla's ToastComponent here; on 1.8.9 SocialToast is the toast stack
			com.origin.client.client.social.SocialToast.clearAll();
			GuiScreen social = openSocial();
			if (social != null) {
				return social;
			}
			switch (this) {
				case TITLE:
					// GuiOpenEvent hands the menus area its chance to swap in the Origin title.
					return new GuiMainMenu();
				case MENU:
					return preview(make(P + "gui.OriginModMenuScreen"), false, false);
				case MENU_LIST:
					return preview(make(P + "gui.OriginModMenuScreen"), true, false);
				case MOD_SETTINGS:
					// A HUD mod's own page: the settings layout the redesign is judged on.
					return preview(make(P + "gui.OriginModMenuScreen", null, "keystrokes"), false, false);
				case SETTINGS:
					return preview(make(P + "gui.OriginModMenuScreen"), false, true);
				case COSMETICS:
					return make(P + "gui.CosmeticsScreen", (Object) null);
				case FPSCOACH:
					return make(P + "gui.FpsCoachScreen", (Object) null);
				case WAYPOINTS:
					return make(P + "waypoints.WaypointScreen");
				case ITEMSIZE:
					return make(P + "gui.OriginItemSizeScreen");
				case ITEMCOUNTER:
					return make(P + "gui.ItemCounterPickerScreen");
				case QUICKPLAY:
					return make(P + "gui.QuickplayScreen");
				case SHADERS:
					return make(P + "shaders.ShaderBrowserScreen", (Object) null);
				case MENU_CLEAR:
					return preview(make(P + "gui.OriginModMenuScreen"), false, false);
				case FRIENDS:
					callStatic(P + "social.SocialDemo", "install", false);
					return select(make(P + "gui.FriendsScreen", (Object) null),
							staticField(P + "social.SocialDemo", "KESTREL"));
				case FRIENDS_PARTY:
					callStatic(P + "social.SocialDemo", "install", false);
					return select(make(P + "gui.FriendsScreen", (Object) null),
							staticField(P + "gui.FriendsScreen", "PARTY"));
				default:
					// RSHIFT: the Right Shift screen (HUD editor + the Origin centre group).
					return make(P + "hud.HudEditorScreen", true);
			}
		}

		/** The social / social-act shots (1.21.1's list, same states); null for every other kind. */
		private GuiScreen openSocial() {
			Minecraft mc = Minecraft.getMinecraft();
			switch (this) {
				case SOC_CHAT:
					com.origin.client.client.social.SocialDemo.install(false);
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL);
				case SOC_PARTY:
					com.origin.client.client.social.SocialDemo.install(false);
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY);
				case SOC_HOME:
					com.origin.client.client.social.SocialDemo.installHome();
					return new com.origin.client.client.gui.FriendsScreen(null);
				case SOC_OFFLINE:
					com.origin.client.client.social.SocialDemo.installHome();
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select("e5b1d7f3a9c24e6b0d8f2a4c6e1b9d38");   // Lumen
				case SOC_EMPTY:
					return new com.origin.client.client.gui.FriendsScreen(null);
				case SOC_SIGNEDOUT:
					com.origin.client.client.social.SocialDemo.status(
							com.origin.client.client.social.SocialClient.Status.SIGNED_OUT, null);
					return new com.origin.client.client.gui.FriendsScreen(null);
				case SOC_UNREACHABLE:
					com.origin.client.client.social.SocialDemo.status(
							com.origin.client.client.social.SocialClient.Status.UNREACHABLE, null);
					return new com.origin.client.client.gui.FriendsScreen(null);
				case SOC_STRESS_CHAT:
					com.origin.client.client.social.SocialDemo.installStress();
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.firstStressFriend());
				case SOC_STRESS_PARTY:
					com.origin.client.client.social.SocialDemo.installStress();
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY);
				case SOC_TOASTS:
					com.origin.client.client.social.SocialDemo.install(false);
					com.origin.client.client.social.SocialToast.message(mc, com.origin.client.client.social.SocialDemo.KESTREL,
							"Kestrel", "I'm in a lobby on hypixel now, join when you're ready and bring the good pickaxe");
					com.origin.client.client.social.SocialToast.request(mc, "9c4a2e8f6b1d4c7e3a5f9b0d2e8c6a14", "Quartzling");
					com.origin.client.client.social.SocialToast.invite(mc, "1d9f5b3e7a0c4f2d8b6e4a1c9f3d7b52", "Ravenmoor");
					com.origin.client.client.social.SocialToast.warp(mc, "0b7e4d2c9a1f4e6b8c3d5a7f2e9b1c63", "Ember_Fox",
							new com.origin.client.client.social.SocialState.Warp("play.originsmp.net", "1.8.9",
									System.currentTimeMillis(), "0b7e4d2c9a1f4e6b8c3d5a7f2e9b1c63", "Ember_Fox"));
					return new GuiMainMenu();
				case SOC_RSHIFT:
					com.origin.client.client.social.SocialDemo.install(false);
					return make(P + "hud.HudEditorScreen", true);
				case SOC_ADD:
					com.origin.client.client.social.SocialDemo.installHome();
					return new com.origin.client.client.gui.FriendsScreen(null).devSearch("Zed_Nine");
				case SOC_FILTER:
					com.origin.client.client.social.SocialDemo.installHome();
					return new com.origin.client.client.gui.FriendsScreen(null).devSearch("mo");
				case SOC_MENU_FRIEND:
					com.origin.client.client.social.SocialDemo.install(false);
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL).devMenu();
				case SOC_MENU_INVITE:
					com.origin.client.client.social.SocialDemo.install(false);
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY).devMenu();
				case SOC_NOTICE:
					com.origin.client.client.social.SocialDemo.install(false);
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL)
							.devNotice("Party invite sent to Kestrel.", false);
				case ACT_HOVER:
				case ACT_INVITE:
				case ACT_CHAT:
				case ACT_ACCEPT:
				case ACT_SEARCH_ENTER:
				case ACT_DRAFT:
				case ACT_DRAFT_BACK:
				case ACT_TYPE_SEARCH:
					com.origin.client.client.social.SocialDemo.installHome();
					return new com.origin.client.client.gui.FriendsScreen(null);
				case ACT_TOAST_STALE:
					com.origin.client.client.social.SocialDemo.install(false);
					com.origin.client.client.social.SocialToast.request(mc, "00000000000000000000000000000099", "Ghost_One");
					com.origin.client.client.social.SocialToast.invite(mc, "00000000000000000000000000000098", "Ghost_Two");
					return new GuiMainMenu();
				case ACT_MENU_ARM:
				case ACT_DOUBLECLICK:
					com.origin.client.client.social.SocialDemo.install(false);
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL);
				case ACT_PARTY_MENU:
					com.origin.client.client.social.SocialDemo.install(false);
					return new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY);
				case ACT_TOAST_REQUEST:
				case ACT_TOAST_INVITE:
					com.origin.client.client.social.SocialDemo.install(false);
					com.origin.client.client.social.SocialToast.request(mc, "9c4a2e8f6b1d4c7e3a5f9b0d2e8c6a14", "Quartzling");
					com.origin.client.client.social.SocialToast.invite(mc, "1d9f5b3e7a0c4f2d8b6e4a1c9f3d7b52", "Ravenmoor");
					return new GuiMainMenu();
				case SOC_MENU:
					com.origin.client.client.social.SocialDemo.install(false);
					return preview(make(P + "gui.OriginModMenuScreen"), false, false);
				case STY_OPTIONS:
					return new net.minecraft.client.gui.GuiOptions(new GuiMainMenu(), Minecraft.getMinecraft().gameSettings);
				case STY_VIDEO:
					return new net.minecraft.client.gui.GuiVideoSettings(new GuiMainMenu(), Minecraft.getMinecraft().gameSettings);
				case STY_SOUND:
					return new net.minecraft.client.gui.GuiScreenOptionsSounds(new GuiMainMenu(), Minecraft.getMinecraft().gameSettings);
				case STY_WORLDS:
					return new net.minecraft.client.gui.GuiSelectWorld(new GuiMainMenu());
				case STY_CREATE:
					return new net.minecraft.client.gui.GuiCreateWorld(new GuiMainMenu());
				case STY_SERVERS:
					return new net.minecraft.client.gui.GuiMultiplayer(new GuiMainMenu());
				case STY_LOADING: {
					net.minecraft.client.gui.GuiScreenWorking w = new net.minecraft.client.gui.GuiScreenWorking();
					w.displaySavingString("Preparing for world creation...");
					w.displayLoadingString("Building terrain");
					w.setLoadingProgress(40);
					return w;
				}
				default:
					return null;
			}
		}
	}

	// Java 8 has no records: a final value class.
	private static final class Shot {
		private final int w;
		private final int h;
		private final int scale;
		private final Kind kind;

		private Shot(int w, int h, int scale, Kind kind) {
			this.w = w;
			this.h = h;
			this.scale = scale;
			this.kind = kind;
		}

		String name() {
			return kind.name().toLowerCase(java.util.Locale.ROOT) + "_" + w + "x" + h + "_s" + scale + ".png";
		}
	}

	private static final List<Shot> SHOTS = new ArrayList<>();

	/** Scripted steps per ACT_ kind, run one per ~700 ms after the screen is up, then the capture. */
	private static final java.util.Map<Kind, List<java.util.function.Consumer<GuiScreen>>> ACTS = new java.util.EnumMap<>(Kind.class);
	private static int actIdx = 0;

	private static com.origin.client.client.gui.FriendsScreen fs(GuiScreen s) {
		return (com.origin.client.client.gui.FriendsScreen) s;
	}

	@SafeVarargs
	private static List<java.util.function.Consumer<GuiScreen>> steps(java.util.function.Consumer<GuiScreen>... s) {
		return java.util.Arrays.asList(s);
	}

	static {
		// design-px coordinates of the 1080p layout (panel origin 500,250 on screen): see FriendsScreen's layout
		// constants -- the same as 1.21.1's (one OriginWindow frame), so the same scripts drive both
		ACTS.put(Kind.ACT_HOVER, steps(s -> fs(s).devHover(180, 385)));
		ACTS.put(Kind.ACT_INVITE, steps(s -> fs(s).devHover(180, 385), s -> fs(s).devClick(248, 385)));
		ACTS.put(Kind.ACT_ACCEPT, steps(s -> fs(s).devHover(228, 233), s -> fs(s).devClick(228, 233)));
		ACTS.put(Kind.ACT_CHAT, steps(s -> fs(s).devClick(140, 385),
				s -> fs(s).devChars("hello there, ready for the stronghold? I have the good pickaxe and 12 eyes of ender"),
				s -> fs(s).devKey(org.lwjgl.input.Keyboard.KEY_RETURN)));
		ACTS.put(Kind.ACT_MENU_ARM, steps(s -> fs(s).devClick(880, 158), s -> fs(s).devHover(792, 200),
				s -> fs(s).devClick(792, 200)));
		ACTS.put(Kind.ACT_SEARCH_ENTER, steps(s -> fs(s).devChars("zed_nine"), s -> fs(s).devKey(org.lwjgl.input.Keyboard.KEY_RETURN)));
		ACTS.put(Kind.ACT_PARTY_MENU, steps(s -> fs(s).devClick(760, 218)));
		ACTS.put(Kind.ACT_DOUBLECLICK, steps(s -> fs(s).devClick(880, 158), s -> {
			fs(s).devClick(792, 200);   // arms
			fs(s).devClick(792, 200);   // a second click inside 350 ms must be ignored
		}));
		ACTS.put(Kind.ACT_DRAFT, steps(s -> fs(s).devClick(140, 385), s -> fs(s).devChars("draft for ember only"),
				s -> fs(s).devClick(140, 431)));
		ACTS.put(Kind.ACT_DRAFT_BACK, steps(s -> fs(s).devClick(140, 385), s -> fs(s).devChars("draft for ember only"),
				s -> fs(s).devClick(140, 431), s -> fs(s).devClick(140, 385)));
		ACTS.put(Kind.ACT_TYPE_SEARCH, steps(s -> fs(s).devClick(140, 385), s -> fs(s).devClick(150, 106),
				s -> fs(s).devClick(400, 40), s -> fs(s).devChars("ke")));
		ACTS.put(Kind.ACT_TOAST_STALE, steps(s -> {
			com.origin.client.client.social.SocialToast.devClickButton("req:00000000000000000000000000000099");
			com.origin.client.client.social.SocialToast.devClickButton("inv:00000000000000000000000000000098");
		}));
		ACTS.put(Kind.ACT_TOAST_REQUEST, steps(s -> com.origin.client.client.social.SocialToast.devClickButton("req:9c4a2e8f6b1d4c7e3a5f9b0d2e8c6a14")));
		ACTS.put(Kind.ACT_TOAST_INVITE, steps(s -> com.origin.client.client.social.SocialToast.devClickButton("inv:1d9f5b3e7a0c4f2d8b6e4a1c9f3d7b52")));
	}

	private static double savedBgOpacity = -1;
	/** ORIGIN_SHOTS_SET=world: OriginDevWorld captures inside a fresh world instead. */
	private static final boolean WORLD = "world".equals(System.getenv("ORIGIN_SHOTS_SET"));
	/**
	 * ORIGIN_SHOTS_SET=handoff: the startup hand-off film. 1.8.9 has no OriginFrameLog
	 * to wait on, so the first resize waits out the title's startup intro instead
	 * (OriginTitleScreen: HOLD ~1.1 s + the 560 ms DISSOLVE) -- resizing under it
	 * would cut the dissolve out of the film.
	 */
	private static final boolean HANDOFF = "handoff".equals(System.getenv("ORIGIN_SHOTS_SET"));

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
		} else if (HANDOFF) {
			// The loading -> title hand-off (filmed with dev-shots `video`): one title
			// shot, taken only after the startup intro has fully dissolved (see tick).
			SHOTS.add(new Shot(1920, 1080, 0, Kind.TITLE));
		} else if ("design".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// Design review: every screen at the two most common desktop sizes, auto GUI scale.
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

	private OriginDevShots() {
	}

	public static boolean enabled() {
		return DIR != null && !DIR.isEmpty();
	}

	/**
	 * ORIGIN_SHOTS_STYLE (dev-only, only with ORIGIN_SHOTS set): forces the two style options for the whole
	 * run: "buttons" = Button Style Minecraft; "menu" = Main Menu Style Vanilla; "vanilla" = both. Unset/empty =
	 * the player's own settings. The real options are written and put back when the shot matrix finishes.
	 * Called from OriginClientMod.init.
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
			if (mc.theWorld == null && isTitle(mc.currentScreen)) {
				if (nextAt == 0) {
					nextAt = now + (HANDOFF ? 5000 : 1500);
				} else if (now >= nextAt) {
					File output = new File(DIR);
					if (!output.isDirectory() && !output.mkdirs()) {
						com.origin.client.OriginClient.LOGGER.error(
								"[dev-shots] could not create output directory {}", output.getAbsolutePath());
						done = true;
						mc.shutdown();
						return;
					}
					savedScale = mc.gameSettings.guiScale;
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
				case 0: {
					mc.gameSettings.guiScale = s.scale;
					resizeWindow(mc, s.w, s.h);
					resizeRetries = 0;
					phase = 1;
					nextAt = now + 800;
					break;
				}
				case 1: {
					// Fresh screens so entrance animations replay and get captured settled.
					GuiScreen screen = s.kind.open();
					if (screen == null) {
						com.origin.client.OriginClient.LOGGER.warn("[dev-shots] {} skipped: its screen is not available", s.name());
						step++;
						phase = 0;
						nextAt = now + 100;
						break;
					}
					mc.displayGuiScreen(screen);
					actIdx = 0;
					phase = 2;
					nextAt = now + (s.kind == Kind.TITLE ? 1900 : 900);
					break;
				}
				case 2: {
					List<java.util.function.Consumer<GuiScreen>> acts = ACTS.get(s.kind);
					if (acts != null && actIdx < acts.size()) {
						acts.get(actIdx++).accept(mc.currentScreen);
						nextAt = now + 700;
						return;
					}
					// The window manager may apply a resize a little later. Never label a
					// capture with dimensions its framebuffer did not actually reach.
					if ((mc.displayWidth != s.w || mc.displayHeight != s.h) && resizeRetries++ < 8) {
						resizeWindow(mc, s.w, s.h);
						nextAt = now + 350;
						return;
					}
					if (mc.displayWidth != s.w || mc.displayHeight != s.h) {
						throw new IllegalStateException("window stayed at " + mc.displayWidth + "x" + mc.displayHeight
								+ " instead of " + s.w + "x" + s.h);
					}
					grab(mc, DIR, s.name());
					step++;
					phase = 0;
					nextAt = now + 250;
					break;
				}
				default:
					phase = 0;
					break;
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] step {} failed", step, t);
			step++;
			phase = 0;
			nextAt = now + 250;
		}
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
				mc.gameSettings.guiScale = savedScale;
			}
		} catch (Throwable ignored) {
			// dev only
		}
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — {} shots in {}", SHOTS.size(), DIR);
		mc.shutdown();
	}

	// ---- 1.8.9 helpers (shared with OriginDevWorld) ----

	/** The vanilla title screen or the menus area's Origin replacement for it. */
	static boolean isTitle(GuiScreen s) {
		return s instanceof GuiMainMenu || (s != null && s.getClass().getSimpleName().contains("Title"));
	}

	/** 1.8.9: LWJGL 2 window resize (re-creates the window), then Minecraft's own resize. */
	static void resizeWindow(Minecraft mc, int w, int h) throws Exception {
		if (Display.getWidth() != w || Display.getHeight() != h) {
			Display.setDisplayMode(new DisplayMode(w, h));
		}
		mc.resize(w, h);
	}

	/** ScreenShotHelper writes `dir`/screenshots/`name` from the main framebuffer. */
	static void grab(Minecraft mc, String dir, String name) {
		ScreenShotHelper.saveScreenshot(new File(dir), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] captured {}", name);
	}

	/** A screen of another area by class name and constructor arity (null on any failure). */
	static GuiScreen make(String className, Object... args) {
		try {
			Class<?> c = Class.forName(className);
			for (Constructor<?> k : c.getConstructors()) {
				if (k.getParameterTypes().length == args.length && fits(k.getParameterTypes(), args)) {
					return (GuiScreen) k.newInstance(args);
				}
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] could not build {}", className, t);
		}
		return null;
	}

	private static GuiScreen preview(GuiScreen menu, boolean list, boolean settings) {
		Object r = call(menu, "preview", list, settings);
		return r instanceof GuiScreen ? (GuiScreen) r : menu;
	}

	private static GuiScreen select(GuiScreen friends, Object what) {
		Object r = call(friends, "select", what);
		return r instanceof GuiScreen ? (GuiScreen) r : friends;
	}

	static Object call(Object target, String method, Object... args) {
		if (target == null) {
			return null;
		}
		try {
			for (Method m : target.getClass().getMethods()) {
				if (m.getName().equals(method) && m.getParameterTypes().length == args.length
						&& fits(m.getParameterTypes(), args)) {
					return m.invoke(target, args);
				}
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] {}.{} failed", target.getClass().getSimpleName(), method, t);
		}
		return null;
	}

	static Object callStatic(String className, String method, Object... args) {
		try {
			Class<?> c = Class.forName(className);
			for (Method m : c.getMethods()) {
				if (m.getName().equals(method) && m.getParameterTypes().length == args.length
						&& fits(m.getParameterTypes(), args)) {
					return m.invoke(null, args);
				}
			}
		} catch (Throwable t) {
			// that area has not landed: the shot goes without it
		}
		return null;
	}

	private static Object staticField(String className, String field) {
		try {
			return Class.forName(className).getField(field).get(null);
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean fits(Class<?>[] types, Object[] args) {
		for (int i = 0; i < types.length; i++) {
			Object a = args[i];
			Class<?> t = types[i];
			if (a == null) {
				if (t.isPrimitive()) {
					return false;
				}
				continue;
			}
			if (t.isPrimitive()) {
				if ((t == boolean.class && a instanceof Boolean) || (t == int.class && a instanceof Integer)
						|| (t == double.class && a instanceof Double) || (t == float.class && a instanceof Float)) {
					continue;
				}
				return false;
			}
			if (!t.isInstance(a)) {
				return false;
			}
		}
		return true;
	}
}
