package com.origin.client.client.mods;

import com.google.gson.JsonPrimitive;
import com.origin.client.client.OriginClientMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

// The single registry behind the mod menu: every mod's id, display name,
// default enable state, and settings schema, plus the typed read/write
// accessors every feature hook uses. Values persist via ModsConfig; the
// legacy originclient.json feature flags are migrated in on first load.
public final class Mods {
	// A mod definition. Icon comes from the baked atlas (OriginUi) keyed on id.
	// description is the one-line blurb shown under the title on the mod's page.
	public record Mod(String id, String name, String description, boolean defaultOn, List<ModOption> options) {
	}

	// Shared color-picker preset palette (white first = default, then a spread
	// of hues) — the favourites row / quick presets in the color picker.
	public static final int[] PALETTE = {
			0xFFFFFFFF, 0xFFE05555, 0xFF55E055, 0xFF5599FF,
			0xFFFFD855, 0xFF55DDDD, 0xFFFF9944, 0xFFCC66FF};

	public static final List<Mod> ALL = new ArrayList<>();

	// SETTINGS tab (spec §7) — two sub-tabs, stored under their own pseudo-ids
	// via the same read/write accessors the mods use. No CONTROLS sub-tab:
	// keybinds live only inside Zoom and Freelook.
	public static final String GENERAL_ID = "@general";
	public static final String PERFORMANCE_ID = "@performance";

	// Every option here is backed by real behavior — Smart Disconnect prompts
	// before leaving a world (PauseScreenMixin), the rest apply via the tick
	// loop / dedicated mixins. Cosmetic-only toggles that couldn't be honestly
	// implemented in a lightweight Fabric mod were dropped rather than left as
	// save-but-do-nothing switches.
	public static final List<ModOption> GENERAL_SETTINGS = List.of(
			ModOption.toggle("borderlessFullscreen", "Borderless Fullscreen", false),
			ModOption.toggle("rawMouseInput", "Raw Mouse Input", true),
			ModOption.toggle("smartDisconnect", "Smart Disconnect", true).tip("Ask for confirmation before leaving a world or server."),
			ModOption.toggle("disableHotbarScrolling", "Disable Hotbar Scrolling", false),
			ModOption.dropdown("mainMenuStyle", "Main Menu Style", "Origin", "Vanilla"),
			ModOption.toggle("showAchievements", "Show Achievements", true).tip("Show the advancement toast pop-ups."));

	// Entity/Tile Entity Distance are a percentage of your render distance: at
	// 100% nothing extra is culled; lower values stop drawing distant entities /
	// block entities. The FPS caps kick in when the window is unfocused or you're
	// sitting on the main menu. Shader Performance Mode halves every active
	// shaderpack's shadow map resolution + shadow render distance (the biggest
	// GPU lever with shaders on) via IrisShadowDirectivesMixin.
	public static final List<ModOption> PERFORMANCE_SETTINGS = List.of(
			ModOption.toggle("shaderPerformanceMode", "Shader Performance Mode", true).tip("Any shader you load renders shadows at half distance for a big FPS gain. Turn off for full quality; applies instantly."),
			ModOption.toggle("limitUnfocusedFps", "Limit Unfocused FPS", true),
			ModOption.slider("maxUnfocusedFps", "Max Unfocused FPS", 5, 60, 5, 30, "%.0f").under("limitUnfocusedFps"),
			ModOption.slider("maxMainMenuFps", "Max Main Menu FPS", 30, 260, 10, 120, "%.0f"),
			ModOption.slider("entityDistance", "Entity Distance", 10, 100, 5, 100, "%.0f%%"),
			ModOption.slider("tileEntityDistance", "Tile Entity Distance", 10, 100, 5, 100, "%.0f%%"));

	// Vanilla 1.21.1 particle types (net.minecraft.core.particles.ParticleTypes)
	// — the Particle Changer builds a per-type control block from this list so
	// coverage is exhaustive rather than a hand-picked sample.
	private static final String[][] PARTICLE_TYPES = {
			{"ambient_entity_effect", "Ambient Entity Effect"}, {"angry_villager", "Angry Villager"},
			{"block", "Block"}, {"block_marker", "Block Marker"}, {"bubble", "Bubble"},
			{"cloud", "Cloud"}, {"crit", "Crit"}, {"damage_indicator", "Damage Indicator"},
			{"dragon_breath", "Dragon Breath"}, {"dripping_lava", "Dripping Lava"},
			{"falling_lava", "Falling Lava"}, {"landing_lava", "Landing Lava"},
			{"dripping_water", "Dripping Water"}, {"falling_water", "Falling Water"},
			{"dust", "Dust"}, {"dust_color_transition", "Dust Color Transition"},
			{"effect", "Effect"}, {"elder_guardian", "Elder Guardian"},
			{"enchanted_hit", "Enchanted Hit"}, {"enchant", "Enchant"}, {"end_rod", "End Rod"},
			{"entity_effect", "Entity Effect"}, {"explosion_emitter", "Explosion Emitter"},
			{"explosion", "Explosion"}, {"gust", "Gust"}, {"small_gust", "Small Gust"},
			{"gust_emitter_large", "Gust Emitter Large"}, {"gust_emitter_small", "Gust Emitter Small"},
			{"sonic_boom", "Sonic Boom"}, {"falling_dust", "Falling Dust"}, {"firework", "Firework"},
			{"fishing", "Fishing"}, {"flame", "Flame"}, {"infested", "Infested"},
			{"cherry_leaves", "Cherry Leaves"}, {"sculk_soul", "Sculk Soul"},
			{"sculk_charge", "Sculk Charge"}, {"sculk_charge_pop", "Sculk Charge Pop"},
			{"soul_fire_flame", "Soul Fire Flame"}, {"soul", "Soul"}, {"flash", "Flash"},
			{"happy_villager", "Happy Villager"}, {"composter", "Composter"}, {"heart", "Heart"},
			{"instant_effect", "Instant Effect"}, {"item", "Item"},
			{"vibration", "Vibration"}, {"item_slime", "Item Slime"}, {"item_cobweb", "Item Cobweb"},
			{"item_snowball", "Item Snowball"}, {"large_smoke", "Large Smoke"}, {"lava", "Lava"},
			{"mycelium", "Mycelium"}, {"note", "Note"}, {"poof", "Poof"}, {"portal", "Portal"},
			{"rain", "Rain"}, {"smoke", "Smoke"}, {"white_smoke", "White Smoke"},
			{"sneeze", "Sneeze"}, {"spit", "Spit"}, {"squid_ink", "Squid Ink"},
			{"underwater", "Underwater"}, {"splash", "Splash"}, {"witch", "Witch"},
			{"bubble_pop", "Bubble Pop"}, {"current_down", "Current Down"},
			{"bubble_column_up", "Bubble Column Up"}, {"nautilus", "Nautilus"}, {"dolphin", "Dolphin"},
			{"campfire_cosy_smoke", "Campfire Cosy Smoke"}, {"campfire_signal_smoke", "Campfire Signal Smoke"},
			{"dripping_honey", "Dripping Honey"}, {"falling_honey", "Falling Honey"},
			{"landing_honey", "Landing Honey"}, {"falling_nectar", "Falling Nectar"},
			{"falling_spore_blossom", "Falling Spore Blossom"}, {"ash", "Ash"},
			{"crimson_spore", "Crimson Spore"}, {"warped_spore", "Warped Spore"},
			{"spore_blossom_air", "Spore Blossom Air"}, {"dripping_obsidian_tear", "Dripping Obsidian Tear"},
			{"falling_obsidian_tear", "Falling Obsidian Tear"}, {"landing_obsidian_tear", "Landing Obsidian Tear"},
			{"reverse_portal", "Reverse Portal"}, {"white_ash", "White Ash"},
			{"small_flame", "Small Flame"}, {"snowflake", "Snowflake"},
			{"dripping_dripstone_lava", "Dripping Dripstone Lava"}, {"falling_dripstone_lava", "Falling Dripstone Lava"},
			{"dripping_dripstone_water", "Dripping Dripstone Water"}, {"falling_dripstone_water", "Falling Dripstone Water"},
			{"glow_squid_ink", "Glow Squid Ink"}, {"glow", "Glow"}, {"wax_on", "Wax On"},
			{"wax_off", "Wax Off"}, {"electric_spark", "Electric Spark"}, {"scrape", "Scrape"},
			{"shriek", "Shriek"}, {"egg_crack", "Egg Crack"}, {"dust_plume", "Dust Plume"},
			{"trial_spawner_detection", "Trial Spawner Detection"}, {"raid_omen", "Raid Omen"},
			{"trial_omen", "Trial Omen"}, {"ominous_spawning", "Ominous Spawning"},
			{"vault_connection", "Vault Connection"}, {"pale_oak_leaves", "Pale Oak Leaves"},
	};

	static {
		// ---- HUD readouts ----
		add("fps", "FPS", "Frames-per-second readout.", false,
				ModOption.toggle("reverseOrder", "Reverse Order", false),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBrackets", "Show Brackets", false),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.header("Color"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF));

		add("cps", "CPS", "Clicks per second.", false,
				ModOption.toggle("rightClick", "Right Click CPS", false),
				ModOption.toggle("showText", "Show CPS Text", true),
				ModOption.toggle("reverseText", "Reverse Text", false),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.header("Color"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF));

		add("togglesprint", "Toggle Sneak/Sprint", "Hands-free sprint and sneak.", false,
				ModOption.toggle("hud", "Toggle Sneak/Sprint HUD", true),
				ModOption.toggle("sprint", "Toggle Sprint", true).tip("Sprint stays on after one press of the toggle key."),
				ModOption.toggle("sneak", "Toggle Sneak", true).tip("Sneak stays on after one press of the toggle key."),
				ModOption.toggle("flyBoost", "Fly Boost", false).tip("Multiplies your creative/spectator flight speed."),
				ModOption.slider("flyBoostAmount", "Fly Boost Amount", 1, 5, 0.1, 2, "%.1fx").under("flyBoost"),
				ModOption.keybind("key", "Sprint key", -1),
				ModOption.dropdown("mode", "Mode", "Toggle", "Hold"));

		add("zoom", "Zoom", "Smooth, configurable zoom.", true,
				ModOption.keybind("key", "Zoom Key", GLFW.GLFW_KEY_C),
				ModOption.toggle("toggleZoom", "Toggle Zoom", false).tip("Press once to zoom, press again to unzoom (instead of holding)."),
				ModOption.toggle("smoothCamera", "Smooth Camera Movement", false).tip("Eases camera motion so the view glides instead of snapping."),
				ModOption.toggle("smoothZoom", "Smooth Zoom In/Out", true).tip("Animates the FOV change when zooming instead of an instant jump."),
				ModOption.toggle("scrollZoom", "Scroll to Zoom", true).tip("While zoomed, scroll the mouse wheel to change the zoom level."),
				ModOption.slider("scrollSpeed", "Scroll Zoom Speed", 0.1, 2.0, 0.1, 1.0, "%.1fx").under("scrollZoom"),
				ModOption.slider("fov", "Zoom Level", 10, 60, 1, 30, "%.0f"),
				ModOption.slider("sensitivity", "Zoomed Sensitivity", 0.1, 2.0, 0.05, 1.0, "%.2fx"));

		add("armorhud", "Armor Status", "Armor pieces and durability.", false,
				ModOption.dropdown("listMode", "List Mode", "Horizontal", "Vertical").tip("Lay the armor pieces out in a row or a column."),
				ModOption.dropdown("durabilityPos", "Durability Position", "Right", "Left", "Below", "Hidden").tip("Where the durability number sits per piece — Hidden turns it off."),
				ModOption.toggle("itemCount", "Item Count", true).tip("Show the stack count on held items (e.g. blocks in hand)."),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.dropdown("damageDisplay", "Damage Display Type", "Value", "Percent").tip("Show remaining durability as a raw number or a percentage."),
				ModOption.dropdown("damageThreshold", "Damage Threshold Type", "Percent", "Value").tip("Whether Damage Color kicks in by percent remaining (<25%) or raw durability left (<50)."),
				ModOption.header("Color"),
				ModOption.color("textColor", "Text Color", 0xFFFFFFFF),
				ModOption.color("damageColor", "Damage Color", 0xFFE05555).tip("Colour the durability text turns once a piece is low."));

		add("keystrokes", "Key Strokes", "On-screen key display.", false,
				ModOption.toggle("showClicks", "Show Clicks", true),
				ModOption.toggle("arrows", "Replace Names With Arrows", false),
				ModOption.toggle("showMovement", "Show Movement Keys", true),
				ModOption.toggle("showSpace", "Show Space Bar", true),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("border", "Border", true),
				ModOption.slider("borderThickness", "Border Thickness", 0, 4, 0.5, 1, "%.1f").under("border"),
				ModOption.slider("keyFadeDelay", "Key Fade Delay", 0, 1000, 50, 200, "%.0fms").tip("How long a key stays highlighted after you release it."),
				ModOption.slider("spacebarThickness", "Spacebar Thickness", 1, 10, 1, 4, "%.0f"),
				ModOption.header("Color"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF),
				ModOption.color("textColorPressed", "Text Color (Pressed)", 0xFF121212),
				ModOption.color("bgColor", "Background Color", 0x99000000),
				ModOption.color("bgColorPressed", "Background Color (Pressed)", 0xFFFFFFFF),
				ModOption.color("borderColor", "Border Color", 0x66FFFFFF));

		add("coords", "Coordinates", "Position, direction, and biome.", true,
				ModOption.toggle("x", "X Coordinate", true),
				ModOption.toggle("y", "Y Coordinate", true),
				ModOption.toggle("z", "Z Coordinate", true),
				ModOption.toggle("renderers", "C (Active Renderers)", false).tip("Show how many chunk sections are currently rendered."),
				ModOption.toggle("direction", "Direction", true),
				ModOption.toggle("biome", "Biome", true),
				ModOption.dropdown("listMode", "List Mode", "Vertical", "Horizontal"),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showWhileTyping", "Show While Typing", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.toggle("copyClipboard", "Copy Coords To Clipboard", true),
				ModOption.toggle("decimal", "Decimal Coordinates", false),
				ModOption.header("Color"),
				ModOption.color("bgColor", "Background Color", 0x99000000),
				ModOption.color("borderColor", "Border Color", 0x66FFFFFF));

		add("potionhud", "Potion Effects", "Active status effects.", false,
				ModOption.toggle("vanillaDisplay", "Vanilla Display (top-right)", false),
				ModOption.toggle("showInInventory", "Show In Inventory", true),
				ModOption.toggle("showWhileTyping", "Show While Typing", true),
				ModOption.toggle("effectName", "Effect Name", true),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.toggle("minimal", "Minimal Mode", false).tip("Show only the timer, hiding the effect name."),
				ModOption.toggle("formattedDurations", "Formatted Durations", true),
				ModOption.toggle("uppercase", "Uppercase Potion Names", false),
				ModOption.toggle("reversedText", "Reversed Text", false),
				ModOption.toggle("excludePermanent", "Exclude Permanent Effects", false).tip("Hide effects with infinite duration (e.g. beacon buffs)."),
				ModOption.header("Color"),
				ModOption.color("bgColor", "Background Color", 0x99000000),
				ModOption.toggle("colorByEffect", "Color Name Based on Effect", true),
				ModOption.color("textColor", "Text Color", 0xFFFFFFFF),
				ModOption.color("durationColor", "Duration Color", 0xFFB0B0B0));

		add("serveraddress", "Server Address", "Current server IP.", false,
				ModOption.toggle("serverIcon", "Display Server Icon", true),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.header("Color"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF));

		add("scoreboard", "Scoreboard", "Server scoreboard styling.", false,
				ModOption.toggle("hideNumbers", "Hide Numbers", false),
				ModOption.toggle("hideScoreboard", "Hide Scoreboard", false),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.slider("borderThickness", "Border Thickness", 0, 4, 0.5, 1, "%.1f"),
				ModOption.toggle("border", "Border", true),
				ModOption.keybind("toggleKey", "Toggle Scoreboard", -1),
				ModOption.toggle("displayToggleMessage", "Display Toggle Message", true),
				ModOption.header("Color"),
				ModOption.color("bgColor", "Background Color", 0x99000000),
				ModOption.color("headerColor", "Header Color", 0xFFFFFFFF),
				ModOption.color("borderColor", "Border Color", 0x66FFFFFF));

		// ---- gameplay / camera ----
		add("freelook", "Freelook", "Look around without turning.", true,
				ModOption.dropdown("listMode", "List Mode", "Third Person", "First Person"),
				ModOption.toggle("invertPitch", "Invert Pitch", false),
				ModOption.toggle("invertYaw", "Invert Yaw", false),
				ModOption.toggle("toggle", "Toggle Freelook", false),
				ModOption.toggle("smoothCamera", "Smooth Camera Movement", true).tip("Eases camera motion so the view glides instead of snapping."),
				ModOption.keybind("key", "Freelook Key", GLFW.GLFW_KEY_LEFT_ALT));

		add("fullbright", "Lighting", "Full-bright and brightness boost.", false,
				ModOption.toggle("fullBright", "Full Bright", true).tip("Lights the whole world to maximum brightness."),
				ModOption.keybind("key", "Full Bright Toggle", -1),
				ModOption.slider("gamma", "Boost Factor", 1, 10, 0.5, 5, "%.1fx").tip("Fine brightness boost — only applies when Full Bright is off."));

		// ---- world overlays ----
		// No chroma anywhere on Block Outline (reverted — it doesn't work on
		// the outline renderer): plain color + width only.
		add("blockoverlay", "Block Outline", "Selection outline and overlay.", false,
				ModOption.toggle("outline", "Block Outline", true),
				ModOption.slider("thickness", "Block Outline Width", 1, 10, 1, 1, "%.0f").under("outline"),
				ModOption.color("color", "Block Outline Color", 0xFFFFFFFF).under("outline"),
				ModOption.toggle("overlay", "Block Overlay", false),
				ModOption.color("overlayColor", "Block Overlay Color", 0x55FFFFFF).under("overlay"),
				ModOption.toggle("side", "Side", false),
				ModOption.toggle("showHiddenFoliage", "Show Hidden Foliage", false));

		add("chunkborders", "Chunk Borders", "Visualize chunk boundaries.", false,
				ModOption.keybind("key", "Toggle Chunk Borders", GLFW.GLFW_KEY_F9),
				ModOption.toggle("grid", "Grid", true),
				ModOption.slider("gridSize", "Grid Size", 1, 16, 1, 16, "%.0f").under("grid"),
				ModOption.slider("thickness", "Line Thickness", 1, 3, 1, 1, "%.0f").under("grid"),
				ModOption.color("color", "Grid Color", 0xFF55DDDD).under("grid"),
				ModOption.toggle("innerCorners", "Inner Corners", true).tip("Mark the corners inside your current chunk."),
				ModOption.slider("innerThickness", "Line Thickness", 1, 3, 1, 1, "%.0f").under("innerCorners"),
				ModOption.color("innerColor", "Inner Chunk Corner Color", 0xFFFFD855).under("innerCorners"),
				ModOption.toggle("outerCorners", "Outer Corners", true).tip("Mark the corners of the neighbouring chunks."),
				ModOption.slider("outerThickness", "Line Thickness", 1, 3, 1, 1, "%.0f").under("outerCorners"),
				ModOption.color("outerColor", "Outer Chunk Corner Color", 0xFFE05555).under("outerCorners"));

		add("hitboxes", "Hitbox", "Entity hitbox rendering.", false,
				ModOption.dropdown("linePattern", "Line Pattern", "Solid", "Dashed", "Dotted"),
				ModOption.slider("maxDistance", "Max Showable Distance", 8, 128, 8, 64, "%.0f"),
				ModOption.toggle("players", "Players", true),
				ModOption.slider("lineWidth", "Line Width", 1, 4, 0.5, 1, "%.1f").under("players"),
				ModOption.color("lineColor", "Line Color", 0xFFFFFFFF).under("players"),
				ModOption.toggle("showHittable", "Show Hittable Color", false).under("players"),
				ModOption.toggle("showDamaged", "Show Damaged Color", false).under("players"),
				ModOption.toggle("showLookVector", "Show Look Vector", false).tip("Draw a line showing where the entity is looking.").under("players"),
				ModOption.header("Entity Types"),
				ModOption.toggle("items", "Items", true),
				ModOption.toggle("itemFrames", "Item Frames", true),
				ModOption.toggle("witherSkulls", "Wither Skulls", true),
				ModOption.toggle("fireballs", "Fireballs", true),
				ModOption.toggle("projectiles", "Projectiles", true),
				ModOption.toggle("passive", "Passive", true),
				ModOption.toggle("expOrbs", "Exp Orbs", true),
				ModOption.toggle("fireworks", "Fireworks", true),
				ModOption.toggle("snowballs", "Snowballs", true),
				ModOption.toggle("arrows", "Arrows", true),
				ModOption.toggle("monsters", "Monsters", true),
				ModOption.toggle("other", "Other Entities", true));

		add("nametags", "Nametags", "Name tag rendering tweaks.", false,
				ModOption.toggle("textShadow", "Nametag Text Shadow", true),
				ModOption.toggle("thirdPerson", "Third Person Nametag", true),
				ModOption.toggle("displayToggleMessage", "Display Toggle Nametags Message", true),
				ModOption.keybind("toggleAll", "Toggle All Nametags", -1),
				ModOption.keybind("togglePlayers", "Toggle Player Nametags Only", -1),
				ModOption.toggle("hideInF1", "Hide Nametags in F1", true),
				ModOption.slider("opacity", "Nametag Opacity", 0.1, 1.0, 0.05, 1.0, "%.0f%%"),
				ModOption.toggle("replaceOwnColor", "Replace Own Nametag Color", false));

		add("weather", "Weather Changer", "Force a client weather mode.", false,
				ModOption.dropdown("mode", "Weather Mode", "Clear", "Rain", "Thunder", "Snow"),
				ModOption.toggle("thunder", "Thunder", false),
				ModOption.toggle("playThunderSounds", "Play Thunder Sounds", true).under("thunder"));

		add("timechanger", "Time Changer", "Set a fixed client time of day.", false,
				ModOption.slider("time", "Time", 0, 24000, 100, 6000, "%.0f"),
				ModOption.toggle("useRealTime", "Use Real Current Time", false),
				ModOption.keybind("increaseKey", "Increase Time", GLFW.GLFW_KEY_RIGHT_BRACKET),
				ModOption.keybind("decreaseKey", "Decrease Time", GLFW.GLFW_KEY_LEFT_BRACKET),
				ModOption.toggle("timePassage", "Time Passage", false),
				ModOption.slider("speed", "Speed", 0.1, 10, 0.1, 1.0, "%.1fx").under("timePassage"));

		add("motionblur", "Motion Blur", "Frame-blend motion blur.", false,
				ModOption.slider("amount", "Strength", 0, 10, 1, 3, "%.0f").tip("0 = off, 10 = maximum blur; smooth in between."));

		add("chat", "Chat", "Chat behavior and appearance.", false,
				ModOption.toggle("unlimited", "Unlimited Chat", false).tip("Remove the limit on stored chat history length."),
				ModOption.toggle("stackSpam", "Stack Spam Messages", true).tip("Collapse repeated messages into one line with a counter."),
				ModOption.toggle("textShadow", "Text Shadow in Chat", true),
				ModOption.toggle("keepHistory", "Keep Chat History", true),
				ModOption.toggle("smoothChat", "Smooth Chat", true).tip("Animate new messages sliding into the chat."),
				ModOption.toggle("timestamps", "Timestamps", false),
				ModOption.slider("opacity", "Background Opacity", 0.0, 1.0, 0.05, 0.5, "%.0f%%"),
				ModOption.slider("scale", "Scale", 0.5, 1.0, 0.05, 1.0, "%.0f%%"));

		add("particles", "Particle Changer", "Per-particle visibility and styling.", false,
				buildParticleOptions());
	}

	// Assembles the Particle Changer option list: global controls, then a
	// collapsible block per vanilla particle type (spec §5 — full coverage).
	private static ModOption[] buildParticleOptions() {
		List<ModOption> o = new ArrayList<>();
		o.add(ModOption.header("General"));
		o.add(ModOption.toggle("hideFirstPerson", "Hide First Person Particles", false).tip("Hides particles spawned right next to you in first person."));
		o.add(ModOption.toggle("hideBlockBreak", "Hide Block-Breaking Particle", false));
		o.add(ModOption.toggle("hideAll", "Hide All Particles", false));
		o.add(ModOption.header("Global"));
		o.add(ModOption.dropdown("mode", "Particles", "All", "Reduced", "Off"));
		o.add(ModOption.slider("scale", "Scale", 0.1, 2.0, 0.1, 1.0, "%.1fx").tip("Global size multiplier for every particle."));
		o.add(ModOption.slider("multiplier", "Multiplier", 0.1, 2.0, 0.1, 1.0, "%.1fx"));
		o.add(ModOption.header("Particle Types"));
		for (String[] p : PARTICLE_TYPES) {
			String id = p[0];
			o.add(ModOption.toggle("p_" + id, p[1], true));
			o.add(ModOption.toggle("p_" + id + "_players", "Show on Players", true).under("p_" + id));
			o.add(ModOption.toggle("p_" + id + "_entities", "Show on Entities", true).under("p_" + id));
			o.add(ModOption.toggle("p_" + id + "_self", "Show on Self", true).under("p_" + id));
			o.add(ModOption.toggle("p_" + id + "_sound", "Play Sound", false).under("p_" + id));
			o.add(ModOption.slider("p_" + id + "_scale", "Scale", 0.1, 2.0, 0.1, 1.0, "%.1fx").under("p_" + id));
			o.add(ModOption.slider("p_" + id + "_multiplier", "Multiplier", 0.1, 2.0, 0.1, 1.0, "%.1fx").under("p_" + id));
			o.add(ModOption.toggle("p_" + id + "_hide", "Hide Particle", false).under("p_" + id));
		}
		return o.toArray(new ModOption[0]);
	}

	private static void add(String id, String name, boolean defaultOn, ModOption... options) {
		add(id, name, "", defaultOn, options);
	}

	private static void add(String id, String name, String description, boolean defaultOn, ModOption... options) {
		ALL.add(new Mod(id, name, description, defaultOn, List.of(options)));
	}

	private Mods() {
	}

	/** Force the config + one-time migration to load NOW, on the calling
	 *  (client-init / main) thread, so the first HUD frame on the render thread
	 *  never races the lazy load — the "mods don't show until I toggle them"
	 *  bug. Idempotent (ensureLoaded/migrateOnce both self-guard). */
	public static void preload() {
		ModsConfig.ensureLoaded();
		migrateOnce();
	}

	public static Mod byId(String id) {
		for (Mod m : ALL) {
			if (m.id().equals(id)) {
				return m;
			}
		}
		return null;
	}

	// ---- typed access (all read-through to ModsConfig with schema defaults) ----

	public static boolean on(String modId) {
		var v = raw(modId).get("enabled");
		if (v != null) {
			return v.getAsBoolean();
		}
		Mod m = byId(modId);
		return m != null && m.defaultOn();
	}

	public static void setOn(String modId, boolean on) {
		raw(modId).put("enabled", new JsonPrimitive(on));
		ModsConfig.save();
	}

	/** Whether this option exists in the mod's schema at all — lets consumers
	 *  distinguish "off" from "not an option" (per-particle rows, etc.). */
	public static boolean hasOption(String modId, String key) {
		return opt(modId, key) != null;
	}

	public static boolean bool(String modId, String key) {
		var v = raw(modId).get(key);
		if (v != null) {
			return v.getAsBoolean();
		}
		ModOption o = opt(modId, key);
		return o != null && o.defBool;
	}

	public static double num(String modId, String key) {
		var v = raw(modId).get(key);
		if (v != null) {
			return v.getAsDouble();
		}
		ModOption o = opt(modId, key);
		return o == null ? 0 : o.defNum;
	}

	public static int color(String modId, String key) {
		var v = raw(modId).get(key);
		if (v != null) {
			return (int) v.getAsLong();
		}
		ModOption o = opt(modId, key);
		return o == null ? 0xFFFFFFFF : o.defColor;
	}

	public static int keyCode(String modId, String key) {
		var v = raw(modId).get(key);
		if (v != null) {
			return v.getAsInt();
		}
		ModOption o = opt(modId, key);
		return o == null ? -1 : o.defKey;
	}

	public static String mode(String modId, String key) {
		var v = raw(modId).get(key);
		ModOption o = opt(modId, key);
		if (v != null) {
			return v.getAsString();
		}
		return o == null || o.modes == null || o.modes.length == 0 ? "" : o.modes[0];
	}

	public static void set(String modId, String key, boolean v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	public static void set(String modId, String key, double v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	public static void set(String modId, String key, int v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	public static void set(String modId, String key, String v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	public static boolean metaBool(String key, boolean def) {
		ModsConfig.ensureLoaded();
		var v = ModsConfig.META.get(key);
		return v == null ? def : v.getAsBoolean();
	}

	public static void setMetaBool(String key, boolean v) {
		ModsConfig.ensureLoaded();
		ModsConfig.META.put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	/** Force the whole mod / HUD / meta store to disk in one pass. Every
	 *  mutation already saves eagerly, so this is the authoritative "save once
	 *  on exit" flush the client-stopping hook uses — a belt-and-braces write
	 *  that guarantees on-screen positions and settings land even if some path
	 *  ever skipped its eager save. */
	public static void flush() {
		ModsConfig.ensureLoaded();
		ModsConfig.save();
	}

	private static Map<String, com.google.gson.JsonElement> raw(String modId) {
		ModsConfig.ensureLoaded();
		migrateOnce();
		return ModsConfig.VALUES.computeIfAbsent(modId, k -> new java.util.concurrent.ConcurrentHashMap<>());
	}

	private static ModOption opt(String modId, String key) {
		// The SETTINGS tab stores under pseudo-ids that aren't in ALL, so resolve
		// their schema (and thus their defaults) from the settings lists directly
		// — otherwise every General/Performance option would fall back to 0/false
		// before it's ever touched (e.g. Entity Distance -> 0 = cull everything).
		List<ModOption> opts;
		if (GENERAL_ID.equals(modId)) {
			opts = GENERAL_SETTINGS;
		} else if (PERFORMANCE_ID.equals(modId)) {
			opts = PERFORMANCE_SETTINGS;
		} else {
			Mod m = byId(modId);
			if (m == null) {
				return null;
			}
			opts = m.options();
		}
		for (ModOption o : opts) {
			if (o.key.equals(key)) {
				return o;
			}
		}
		return null;
	}

	// One-time seed from the legacy originclient.json flags so nobody loses
	// their zoom/freelook state; the legacy file stays on disk untouched
	// (the launcher's originUiEnabled bridge field lives there).
	private static boolean migrated = false;

	private static void migrateOnce() {
		if (migrated) {
			return;
		}
		migrated = true;
		if (!ModsConfig.VALUES.isEmpty()) {
			return; // already have real state
		}
		var legacy = OriginClientMod.FEATURES;
		seed("zoom", legacy.zoomEnabled);
		seed("freelook", legacy.freelookEnabled);
		seed("coords", legacy.hudInfoEnabled);
		seed("fps", legacy.hudInfoEnabled);
		seed("togglesprint", legacy.toggleSprintEnabled);
		seed("togglesneak", legacy.toggleSneakEnabled);
		seed("fullbright", legacy.fullbrightEnabled);
		ModsConfig.VALUES.computeIfAbsent("zoom", k -> new java.util.concurrent.ConcurrentHashMap<>())
				.put("fov", new JsonPrimitive(legacy.zoomFov));
	}

	private static void seed(String id, boolean on) {
		ModsConfig.VALUES.computeIfAbsent(id, k -> new java.util.concurrent.ConcurrentHashMap<>())
				.put("enabled", new JsonPrimitive(on));
	}
}
