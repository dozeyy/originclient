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
			ModOption.toggle("transparentMenus", "Transparent Menus", false).tip("Menus you open in a world (pause, options, inventory, the Origin menu) show the game behind them: no blur, no dimming, no panel. Off keeps the normal styled menus."),
			ModOption.toggle("rawMouseInput", "Raw Mouse Input", true),
			ModOption.toggle("smartDisconnect", "Smart Disconnect", true).tip("Ask for confirmation before leaving a world or server."),
			ModOption.toggle("disableHotbarScrolling", "Disable Hotbar Scrolling", false),
			ModOption.dropdown("mainMenuStyle", "Main Menu Style", "Origin", "Vanilla")
					.tip("Origin gives the title screen its own layout and puts this version's panorama behind every Minecraft menu and loading screen. Vanilla keeps all of them stock."),
			ModOption.dropdown("menuLayout", "Mod Menu Layout", "Auto", "3 across (compact)", "2 across (large)")
					.tip("How many mod cards fit in a row. Auto fits the screen (up to four). Compact and large scroll down."),
			ModOption.toggle("showAchievements", "Show Achievements", true).tip("Show the advancement toast pop-ups."),
			ModOption.toggle("cosmeticEffects", "Cosmetic Effects", true).tip("Sparkles on capes and crowns, yours and other players'. Off draws none."),
			ModOption.toggle("capePhysics", "Cape Physics", true).tip("Origin capes swing and flow with how you move. Off uses the stiff vanilla cape."));

	// Entity/Tile Entity Distance are a percentage of your render distance: at
	// 100% nothing extra is culled; lower values stop drawing distant entities /
	// block entities. The FPS caps kick in when the window is unfocused or you're
	// sitting on the main menu. Shader Performance Mode halves every active
	// shaderpack's shadow map resolution + shadow render distance (the biggest
	// GPU lever with shaders on) via IrisShadowDirectivesMixin.
	public static final List<ModOption> PERFORMANCE_SETTINGS = List.of(
			ModOption.toggle("shaderPerformanceMode", "Shader Performance Mode", true).tip("Any shader you load renders shadows at half distance for a big FPS gain. Turn off for full quality; applies instantly."),
			// Limit Far Shadows: render/ShadowCasterLimit + ShadowCaster*Mixin. Shader packs only.
			ModOption.toggle("farShadowLimit", "Limit Far Shadows", false).tip("With a shader on, mobs and blocks like chests farther away than the distance below stop casting shadows. Saves work every frame in busy places; everything closer looks exactly the same."),
			ModOption.slider("farShadowDistance", "Far Shadow Distance", 16, 160, 8, 64, "%.0f blocks").under("farShadowLimit"),
			// Off-Map Shadows: render/OffMapShadows + SodiumViewportShadowMapMixin + ShadowCasterEntityMixin
			// (+ IrisShadowProjectionMixin: Iris 1.6 keeps the shadow projection in a local).
			ModOption.toggle("offMapShadows", "Off-Map Shadows", false).tip("With a shader on, chunks and mobs that fall outside the shadow map stop being drawn into it. Saves work every frame at long render distances; shadows on everything near you look the same."),
			// Half-Rate Shadows: render/HalfRateShadows + IrisPipelineHalfRateMixin / IrisShadowRendererHalfRateMixin.
			ModOption.toggle("halfRateShadows", "Half-Rate Shadows", false).tip("Shadows update every other frame for more FPS. Moving shadows may look slightly less smooth."),
			// Smart Terrain Updates: render/SmartTerrainUpdates + Sodium*Reuse/Graph/Stamp mixins.
			ModOption.toggle("smartTerrainUpdates", "Smart Terrain Updates", false).tip("With a shader on, the game stops re-working out which chunks you can see on frames where nothing moved or changed. Saves CPU time while you stand still; the picture is exactly the same."),
			ModOption.toggle("limitUnfocusedFps", "Limit Unfocused FPS", true).tip("Caps the frame rate while Minecraft is in the background, and pauses Origin's HUD and cosmetic sparkles until you come back. Saves GPU and battery; the game itself keeps running normally."),
			ModOption.slider("maxUnfocusedFps", "Max Unfocused FPS", 5, 60, 5, 30, "%.0f").under("limitUnfocusedFps"),
			ModOption.slider("maxMainMenuFps", "Max Main Menu FPS", 30, 260, 10, 120, "%.0f"),
			ModOption.slider("entityDistance", "Entity Distance", 10, 100, 5, 100, "%.0f%%"),
			ModOption.slider("tileEntityDistance", "Tile Entity Distance", 10, 100, 5, 100, "%.0f%%"));

	// A speed-up whose Sodium/Iris hooks are off on this player's builds says so in its tooltip
	// (compat/CompatGates: "Off: needs a supported Sodium").
	static {
		com.origin.client.client.compat.CompatNotes.mark(PERFORMANCE_SETTINGS);
	}

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
		// Background defaults (Will, 2026-10-11): every HUD module's bgColor
		// defaults to fully transparent (0x00000000) -- the player-facing
		// "background opacity" is that alpha byte -- EXCEPT Key Strokes, whose
		// keys read as blank boxes without a fill. Only the DEFAULT changed: a
		// colour the player picked is a saved key and wins, so there is no
		// migration. The starter config (default-mods-config.json) carries no
		// bgColor for these modules, so it inherits this default as-is.
		add("fps", "FPS", "Frames-per-second readout.", false,
				ModOption.toggle("reverseOrder", "Reverse Order", false),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBrackets", "Show Brackets", false),
				ModOption.toggle("showBackground", "Show Background", true),
				// Frame pacing (FrameStats). Off by default so the readout looks the same
				// for everyone who never opens these settings.
				ModOption.header("Frame Times"),
				ModOption.toggle("showLows", "Show 1% Low", false),
				ModOption.toggle("showFrameTime", "Show Frame Time (ms)", false),
				ModOption.toggle("showGraph", "Frame Time Graph", false),
				ModOption.header("Color"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF),
				ModOption.color("bgColor", "Background Color", 0x00000000));

		add("cps", "CPS", "Clicks per second.", false,
				ModOption.toggle("rightClick", "Right Click CPS", false),
				ModOption.toggle("showText", "Show CPS Text", true),
				ModOption.toggle("reverseText", "Reverse Text", false),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.header("Color"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF),
				ModOption.color("bgColor", "Background Color", 0x00000000));

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
				ModOption.color("damageColor", "Damage Color", 0xFFE05555).tip("Colour the durability text turns once a piece is low."),
				ModOption.color("bgColor", "Background Color", 0x00000000));

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
				ModOption.color("bgColor", "Background Color", 0x00000000),
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
				ModOption.color("bgColor", "Background Color", 0x00000000),
				ModOption.toggle("colorByEffect", "Color Name Based on Effect", true),
				ModOption.color("textColor", "Text Color", 0xFFFFFFFF),
				ModOption.color("durationColor", "Duration Color", 0xFFB0B0B0));

		add("serveraddress", "Server Address", "Current server IP.", false,
				ModOption.toggle("serverIcon", "Display Server Icon", true),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.header("Color"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF),
				ModOption.color("bgColor", "Background Color", 0x00000000));

		add("scoreboard", "Scoreboard", "Server scoreboard styling.", false,
				ModOption.toggle("hideNumbers", "Hide Numbers", false),
				ModOption.toggle("hideScoreboard", "Hide Scoreboard", false),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.slider("borderThickness", "Border Thickness", 0, 4, 0.5, 1, "%.1f"),
				ModOption.toggle("border", "Border", true),
				ModOption.keybind("toggleKey", "Toggle Scoreboard", -1),
				ModOption.toggle("displayToggleMessage", "Display Toggle Message", true),
				ModOption.header("Color"),
				ModOption.color("bgColor", "Background Color", 0x00000000),
				ModOption.color("headerColor", "Header Color", 0xFFFFFFFF),
				ModOption.color("borderColor", "Border Color", 0x66FFFFFF));

		// ---- Lunar-parity HUD readouts (batch 1, 2026-10-11) ----
		// Each mirrors the option set of Lunar Client's mod of the same name
		// (Lunar's Apollo docs list them per mod), in Origin's shape: the shared
		// text block from textReadout() gives every readout the same Text Shadow /
		// Brackets / Background / Border rows + colours, and HUD scale comes from
		// the HUD editor like every other element. Backgrounds default transparent
		// (Will's rule above). Defaults follow Lunar's where Lunar documents one
		// (Saturation and Direction HUD ship ON in Lunar); everything else is off.
		add("clock", "Clock", "Real-world time on your HUD.", false,
				textReadout(
						ModOption.toggle("militaryTime", "24-Hour Time", false),
						ModOption.toggle("showAmPm", "Show AM/PM", true).tip("Only used with 24-Hour Time off."),
						ModOption.toggle("showSeconds", "Show Seconds", false)));

		add("stopwatch", "Stopwatch", "A start/stop timer on your HUD.", false,
				textReadout(
						ModOption.keybind("startKey", "Start / Stop Key", -1).tip("Press to start the stopwatch; press again to pause it."),
						ModOption.keybind("resetKey", "Reset Key", -1).tip("Stops the stopwatch and sets it back to zero."),
						ModOption.toggle("showMillis", "Show Hundredths", true).tip("Show the time to a hundredth of a second (0:12.34)."),
						ModOption.toggle("hideWhenStopped", "Hide When Stopped", false).tip("Only show the stopwatch while it's running."),
						ModOption.toggle("displayToggleMessage", "Display Toggle Message", true)));

		add("daycounter", "Day Counter", "How many Minecraft days have passed.", false,
				textReadout(
						ModOption.toggle("useWorldTime", "Use World Day", false)
								.tip("On: the world's own day (sleeping skips ahead). Off: days the world has actually been played.")));

		add("ping", "Ping", "Your connection latency to the server.", false,
				textReadout(
						ModOption.slider("updateInterval", "Update Interval", 1, 120, 1, 20, "%.0fs").tip("How often a new ping sample is taken."),
						ModOption.slider("samples", "Averaged Samples", 2, 20, 1, 3, "%.0f").tip("The readout shows the average of this many recent samples."),
						ModOption.toggle("showMs", "Show ms", true),
						ModOption.toggle("showPrefix", "Show \"Ping:\" Prefix", true),
						ModOption.toggle("dynamicColor", "Dynamic Color", true).tip("Colour the number by how good your ping is."),
						ModOption.color("lowColor", "Low Ping Color (0-50)", 0xFF55FF55).under("dynamicColor"),
						ModOption.color("mediumColor", "Medium Ping Color (51-100)", 0xFFFFFF55).under("dynamicColor"),
						ModOption.color("highColor", "High Ping Color (101-200)", 0xFFFFAA00).under("dynamicColor"),
						ModOption.color("extremeColor", "Extreme Ping Color (200+)", 0xFFFF5555).under("dynamicColor"),
						ModOption.toggle("spikeDetection", "Ping Spike Detection", false).tip("Flag a sample that jumps above your recent average."),
						ModOption.slider("mediumSpike", "Medium Spike Threshold", 1, 200, 1, 20, "+%.0fms").under("spikeDetection"),
						ModOption.color("mediumSpikeColor", "Medium Spike Color", 0xFFFFAA00).under("spikeDetection"),
						ModOption.slider("largeSpike", "Large Spike Threshold", 1, 200, 1, 50, "+%.0fms").under("spikeDetection"),
						ModOption.color("largeSpikeColor", "Large Spike Color", 0xFFFF5555).under("spikeDetection")));

		add("memory", "Memory Usage", "How much of Minecraft's memory is in use.", false,
				textReadout(
						ModOption.dropdown("displayMode", "Display", "Percent", "Used / Max", "Both"),
						ModOption.toggle("colorByUsage", "Color Based on Usage", false),
						ModOption.color("lowColor", "Low Usage Color (under 50%)", 0xFF55FF55).under("colorByUsage"),
						ModOption.color("mediumColor", "Medium Usage Color (50-80%)", 0xFFFFFF55).under("colorByUsage"),
						ModOption.color("highColor", "High Usage Color (over 80%)", 0xFFFF5555).under("colorByUsage")));

		// Saturation = Lunar's built-in AppleSkin: an overlay on the hunger bar, a
		// preview of what the held food restores, and food tooltips. The optional
		// text readout is the "HUD" Lunar's own posts mention; it measures 0x0 while
		// off, which keeps it out of the HUD editor (see HudEditorScreen).
		add("saturation", "Saturation", "See hidden hunger saturation and food values.", true,
				ModOption.toggle("overlay", "Saturation Overlay", true).tip("Gold shanks over your hunger bar show your hidden saturation."),
				ModOption.color("overlayColor", "Overlay Color", 0xCCFFC21C).under("overlay"),
				ModOption.toggle("heldHunger", "Show Held Item Hunger", true).tip("Holding food flashes the hunger it would restore."),
				ModOption.toggle("heldSaturation", "Show Held Item Saturation", true).tip("Holding food flashes the saturation it would add."),
				ModOption.toggle("tooltip", "Food Tooltip", true).tip("Food tooltips list the hunger and saturation they restore."),
				ModOption.header("Saturation HUD"),
				ModOption.toggle("showHud", "Saturation HUD", false).tip("A movable \"Saturation: 4.8\" readout."),
				ModOption.toggle("textShadow", "Text Shadow", true).under("showHud"),
				ModOption.toggle("showBrackets", "Show Brackets", false).under("showHud"),
				ModOption.toggle("showBackground", "Show Background", true).under("showHud"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF).under("showHud"),
				ModOption.color("bgColor", "Background Color", 0x00000000).under("showHud"));

		add("packdisplay", "Pack Display", "Shows your active resource pack.", false,
				textReadout(
						ModOption.toggle("packIcon", "Display Pack Icon", true),
						ModOption.toggle("packDescription", "Pack Description", false),
						ModOption.toggle("packExtension", "Pack Extension", false).tip("Keep the \".zip\" on the pack name."),
						ModOption.toggle("showAllPacks", "Show All Packs", false).tip("List every enabled pack, not just the top one."),
						ModOption.toggle("keepStrikethrough", "Keep Strikethrough", true),
						ModOption.toggle("keepObfuscated", "Keep Obfuscated", true).tip("Off: scrambled letters in a pack name are shown plainly.")));

		add("itemcounter", "Item Counter", "Counts chosen items in your inventory.", false,
				// Picked in the shared item grid (ItemCounterPickerScreen), not a list
				// popup, so it has no fixed choice set -- see ItemCounter for the tokens.
				ModOption.multiselect("items", "Items")
						.defaults(ItemCounter.DEFAULTS),
				ModOption.toggle("grouped", "Grouped", false).tip("Merge the counters into one box instead of one box each."),
				ModOption.dropdown("listMode", "List Mode", "Vertical", "Horizontal"),
				ModOption.toggle("hideZero", "Hide Items You Don't Have", false),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.toggle("border", "Border", false),
				ModOption.color("borderColor", "Border Color", 0x66FFFFFF).under("border"),
				ModOption.header("Color"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF),
				ModOption.color("bgColor", "Background Color", 0x00000000));

		add("directionhud", "Direction HUD", "A compass strip showing where you face.", true,
				ModOption.slider("width", "Width", 100, 300, 10, 160, "%.0f"),
				ModOption.toggle("boldDirections", "Bold Directions", false),
				ModOption.toggle("showMarker", "Show Marker", true).tip("The centre notch marking exactly where you face."),
				ModOption.toggle("showMarkerValue", "Show Marker Value", true).tip("Your exact heading in degrees under the marker."),
				ModOption.toggle("showWaypoints", "Show Waypoints", true).tip("Your enabled waypoints appear as coloured dots on the strip."),
				ModOption.toggle("showWithTab", "Show With Tab", false).tip("Keep the strip visible while the player list is open."),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.toggle("border", "Border", false),
				ModOption.color("borderColor", "Border Color", 0x66FFFFFF).under("border"),
				ModOption.header("Color"),
				ModOption.color("directionColor", "Direction Color", 0xFFFFFFFF),
				ModOption.color("markerColor", "Marker Color", 0xFFFFFFFF),
				ModOption.color("bgColor", "Background Color", 0x00000000));

		add("combo", "Combo Counter", "Hits landed in a row without being hit.", false,
				textReadout(
						ModOption.toggle("reverseOrder", "Reverse Order", false).tip("\"3 Combo\" instead of \"Combo: 3\"."),
						ModOption.toggle("hideZero", "Hide When No Combo", false),
				ModOption.toggle("playersOnly", "Players Only", false)
						.tip("Only hits on players count. Hitting mobs is ignored."),
				ModOption.slider("resetAfter", "Reset After", 1, 10, 0.5, 2, "%.1fs")
						.tip("The combo ends if you go this long without landing a hit. Getting hit always ends it.")));

		add("reach", "Reach Display", "Distance of your last hit.", false,
				textReadout(
						ModOption.toggle("reverseOrder", "Reverse Order", false).tip("\"3.02 blocks\" instead of \"Reach: 3.02\"."),
						ModOption.toggle("hideZero", "Hide Zero", false).tip("Hide the readout until you've landed a hit.")));

		// ---- gameplay / camera ----
		add("freelook", "Freelook", "Look around without turning.", true,
				ModOption.dropdown("listMode", "List Mode", "Third Person", "First Person"),
				ModOption.toggle("invertPitch", "Invert Pitch", false),
				ModOption.toggle("invertYaw", "Invert Yaw", false),
				ModOption.toggle("toggle", "Toggle Freelook", false),
				ModOption.toggle("smoothCamera", "Smooth Camera Movement", true).tip("Eases camera motion so the view glides instead of snapping."),
				ModOption.keybind("key", "Freelook Key", GLFW.GLFW_KEY_LEFT_ALT));

		add("fullbright", "Lighting", "Full-bright and brightness boost.", false,
				ModOption.toggle("fullBright", "Full Bright", true).tip("Lights the whole world to maximum brightness. Shaders must be OFF for this to work — a shaderpack does its own lighting and ignores it."),
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

		// Crosshair replaces vanilla's crosshair sprite (GuiCrosshairMixin). Sizes
		// are in real screen pixels, so it looks the same at every GUI scale.
		add("crosshair", "Crosshair", "Design your own crosshair.", false,
				ModOption.dropdown("style", "Style", "Cross", "Dot", "Circle", "Square", "Arrow", "T", "X"),
				ModOption.slider("size", "Size", 1, 20, 1, 6, "%.0f").tip("Length of each arm (the radius for Circle)."),
				ModOption.slider("thickness", "Thickness", 1, 8, 1, 2, "%.0f"),
				ModOption.slider("gap", "Gap", 0, 15, 1, 3, "%.0f").tip("Empty space between the centre and each arm."),
				ModOption.toggle("dot", "Center Dot", false),
				ModOption.slider("dotSize", "Dot Size", 1, 8, 1, 2, "%.0f").under("dot"),
				ModOption.toggle("outline", "Outline", true).tip("A dark edge keeps the crosshair readable on bright blocks."),
				ModOption.slider("outlineThickness", "Outline Thickness", 1, 4, 1, 1, "%.0f").under("outline"),
				ModOption.color("outlineColor", "Outline Color", 0xFF000000).under("outline"),
				ModOption.toggle("showInF5", "Show In F5", true).tip("Keep the crosshair on screen in third person."),
				ModOption.toggle("dynamicColor", "Dynamic Color", false).tip("Change colour when aiming at a mob or player."),
				ModOption.color("enemyColor", "Enemy Color", 0xFFFF5555).under("dynamicColor"),
				ModOption.color("friendlyColor", "Friendly Color", 0xFF55FF55).under("dynamicColor"),
				ModOption.header("Color"),
				ModOption.color("color", "Crosshair Color", 0xFFFFFFFF));

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

		// Line Width / Line Color / Hittable / Damaged are NOT under("players"):
		// they style every hitbox the mod draws, not just other players. They used
		// to be nested there, which matched a renderer that only ever coloured
		// players and left every mob vanilla white.
		// Hittable and Damaged now carry their own colour pickers -- their colours
		// were previously hardcoded constants with no way to change them.
		add("hitboxes", "Hitbox", "Entity hitbox rendering.", false,
				ModOption.dropdown("linePattern", "Line Pattern", "Solid", "Dashed", "Dotted"),
				ModOption.slider("maxDistance", "Max Showable Distance", 8, 128, 8, 64, "%.0f"),
				ModOption.slider("lineWidth", "Line Width", 1, 6, 1, 1, "%.0f"),
				ModOption.color("lineColor", "Line Color", 0xFFFFFFFF),
				ModOption.toggle("showHittable", "Show Hittable Color", false).tip("Tint the hitbox of whatever your crosshair is on."),
				ModOption.color("hittableColor", "Hittable Color", 0xFF7ACF9E).under("showHittable"),
				ModOption.toggle("showDamaged", "Show Damaged Color", false).tip("Tint an entity's hitbox while it's taking damage."),
				ModOption.color("damagedColor", "Damaged Color", 0xFFE05555).under("showDamaged"),
				// Not under("players") and not self-only: the look ray is drawn on
				// every OTHER entity. Drawing it on yourself is pointless -- vanilla
				// suppresses the camera entity in the main pass, so you could never
				// see your own ray anyway; all it did was get picked up by Iris's
				// shadow pass and render as a shadow on the ground.
				ModOption.toggle("players", "Players", true),
				ModOption.toggle("showLookVector", "Show Look Vector", false).tip("Draw a line showing where other players and mobs are looking."),
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

		add("nametags", "Nametags", "Customize name tag rendering.", false,
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("thirdPerson", "Third Person Nametag", true),
				ModOption.toggle("hideInF1", "Hide Nametags in F1", true),
				ModOption.slider("opacity", "Opacity", 0.1, 1.0, 0.05, 1.0, "%.0f%%"),
				ModOption.color("backgroundColor", "Background Color", 0x66000000)
						.tip("The tint + opacity of the box behind every nametag."),
				ModOption.toggle("overrideColor", "Custom Text Color", false)
						.tip("Recolour every player nametag's text to a colour you pick."),
				ModOption.color("textColor", "Text Color", 0xFFFFFFFF).under("overrideColor"),
				ModOption.header("Your Nametag"),
				ModOption.toggle("ownOverride", "Override Your Own Nametag", false)
						.tip("Give your own tag a different colour from everyone else's."),
				ModOption.color("ownTextColor", "Your Text Color", 0xFFFFFFFF).under("ownOverride"),
				ModOption.color("ownBackgroundColor", "Your Background Color", 0x66000000).under("ownOverride"),
				ModOption.header("Toggle Keybinds"),
				ModOption.keybind("toggleAll", "Toggle All Nametags", -1),
				ModOption.keybind("togglePlayers", "Toggle Player Nametags Only", -1),
				ModOption.toggle("displayToggleMessage", "Show Toggle Message", true));

		// Item Size Customizer — the per-item scales live in ItemSizes (its own file);
		// this entry is just the on/off switch + the card that opens the grid screen.
		add("itemsize", "Item Size", "Set custom dropped-item render sizes per item.", false);

		add("tablist", "Tab Editor", "Customize the player list overlay.", false,
				ModOption.toggle("stickyToggle", "Sticky Tab", true)
						.tip("Tap the list key to lock it open; hold and release for vanilla peek."),
				ModOption.keybind("tabKey", "Tab Keybind", GLFW.GLFW_KEY_TAB)
						.tip("Two-way synced with Minecraft's Player List key in Controls."),
				ModOption.header("Players"),
				ModOption.toggle("displayHeads", "Display Player Heads", true),
				ModOption.toggle("hideNpcs", "Hide NPCs", false)
						.tip("Hide fake players — offline-UUID entities like Citizens NPCs."),
				ModOption.toggle("moveSelfTop", "Move Yourself to Top", false),
				ModOption.toggle("highlightSelf", "Highlight Your Name", false),
				ModOption.color("selfColor", "Your Name Color", 0xFFFFD700).under("highlightSelf"),
				ModOption.header("Health & Combat"),
				ModOption.toggle("showHealth", "Show Player Health", false)
						.tip("Their health 0-20 beside the ping — a \"?\" when they're too far to track."),
				ModOption.toggle("combatHighlight", "Highlight Combat Targets", false)
						.tip("Anyone you hit turns red and jumps to the top of the list."),
				ModOption.color("combatColor", "Combat Color", 0xFFFF5555).under("combatHighlight"),
				ModOption.slider("combatSeconds", "Combat Time", 5, 60, 1, 15, "%.0fs")
						.under("combatHighlight")
						.tip("How long after your last hit they stay marked."),
				ModOption.header("Ping"),
				ModOption.toggle("hidePing", "Hide Ping", false),
				ModOption.toggle("pingAsNumber", "Show Ping as a Number", false),
				ModOption.header("Header & Footer"),
				ModOption.toggle("disableHeader", "Disable Header", false),
				ModOption.toggle("disableFooter", "Disable Footer", false),
				ModOption.color("headerColor", "Header Color", 0xFFFFFFFF),
				ModOption.color("footerColor", "Footer Color", 0xFFFFFFFF),
				ModOption.header("Background"),
				ModOption.color("backgroundColor", "Background Color", 0x80000000));

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

		// Color Saturation: a full-screen colour grade (ColorGrade + the
		// origin_color_grade post shader). Each slider's MIDDLE (1.0x) is normal;
		// down decreases, up increases. Applies to the whole rendered frame.
		add("colorsaturation", "Color Saturation", "Grade the whole screen's colour.", false,
				ModOption.slider("saturation", "Saturation", 0, 2, 0.05, 1.0, "%.2fx").tip("Middle = normal. Down = greyer, up = more vivid."),
				ModOption.slider("brightness", "Brightness", 0, 2, 0.05, 1.0, "%.2fx").tip("Middle = normal. Down = darker, up = brighter."),
				ModOption.slider("contrast", "Contrast", 0, 2, 0.05, 1.0, "%.2fx").tip("Middle = normal. Down = flatter, up = punchier."));

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

		// Waypoints: in-world markers (beam + label + distance). Its list + create/
		// edit UI live in WaypointScreen (opened from the card or a keybind), so only
		// the system-wide toggles live in the schema here. Death Waypoints / Confirm
		// Before Delete are read live by Waypoints/WaypointScreen.
		add("waypoints", "Waypoints", "In-world markers with beams, labels, and distance.", false,
				ModOption.toggle("deathWaypoints", "Death Waypoints", true).tip("Auto-drop a waypoint where you die."),
				ModOption.toggle("confirmDelete", "Confirm Before Delete", true).tip("Ask before removing a waypoint."),
				ModOption.toggle("locatorBar", "Locator Bar", false)
						.tip("A compass strip in the XP bar showing every waypoint's direction (max 2000 blocks)."),
				ModOption.toggle("separateBar", "Separate Bar", false)
						.tip("Detach the locator bar from the XP bar into a standalone, movable HUD element."));

		// ---- interface ----
		// JEI is not Origin code — it's mezz's mod, bundled jar-in-jar (see
		// THIRD-PARTY-LICENSES.txt). That makes its toggle different in kind from
		// every other mod here: the others gate code we own and simply don't run,
		// but Fabric Loader loads JEI at startup and it CANNOT be unloaded. So
		// "off" can't mean "absent" — it means Origin suppresses JEI's entire
		// player-visible surface (render + input) at its two root event handlers,
		// leaving vanilla to draw and handle everything. See JeiGuiEventHandlerMixin
		// / JeiClientInputHandlerMixin for why those two are the choke points.
		// Default off, like every mod here except zoom/coords/freelook.
		add("jei", "JEI", "Item and recipe viewer.", false);

		registerLunarBatch2();
		registerLunarBatch4b();
		registerLunarBatch3b();
		registerLunarBatch4a();
		registerLunarBatch3a();
		registerLunarBedwars();
		registerOriginalsPortalCalc();
		registerOriginalsFpsCoach();
		registerOriginalsFriends();
	}

	// ---- Origin originals: Friends & Party (2026-10-12, docs/SOCIAL.md) ----
	// The Friends window itself (friends, chat, party) always works; this mod
	// switches what Friends does in the world and on screen: pop-ups, party chat
	// lines in chat, and the party features. Markers / health / map dots / pings
	// only ever work in LAN worlds (social/FairPlay, Will's fair-play baseline).
	// On by default: a party you join should show up without a trip to the menu.
	private static void registerOriginalsFriends() {
		add("friends", "Friends & Party", "Pop-ups from friends, party chat, and your party in LAN worlds.", true,
				ModOption.header("Pop-ups"),
				ModOption.toggle("toastMessages", "Messages", true)
						.tip("A pop-up when a friend messages you. Click it to reply."),
				ModOption.toggle("toastRequests", "Friend Requests", true),
				ModOption.toggle("toastInvites", "Party Invites", true),
				ModOption.toggle("toastWarps", "Party Warps", true)
						.tip("When your party leader warps the party, a pop-up with a Join button."),
				ModOption.header("Party"),
				ModOption.toggle("partyChatInChat", "Party Chat In Chat", true)
						.tip("Show party messages in chat as [Party]. Send your own with /originpc <message>."),
				ModOption.toggle("highlightNames", "Highlight Party Names", true)
						.tip("Party members' name tags in the party colour. LAN worlds only."),
				ModOption.toggle("markers", "Party Markers", true)
						.tip("A marker with name and distance over each party member, even through walls. LAN worlds only."),
				ModOption.toggle("partyHud", "Party HUD", true)
						.tip("Your party on screen: health, distance and direction in LAN worlds, who's online everywhere else."),
				ModOption.toggle("mapMarkers", "On Minimap And Locator Bar", true)
						.tip("Party members on the minimap and the locator bar. LAN worlds only."),
				ModOption.toggle("showPings", "Pings", true)
						.tip("Show pings from your party for 60 seconds. Set the Ping Party key in Controls. LAN worlds only."),
				ModOption.color("partyColor", "Party Color", 0xFF5FD09B),
				ModOption.header("Party HUD"),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.toggle("border", "Border", false),
				ModOption.color("borderColor", "Border Color", 0x66FFFFFF).under("border"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF),
				ModOption.color("bgColor", "Background Color", 0x00000000));
	}

	// ---- Lunar parity, batch 4b: WorldEdit CUI (2026-10-11) ----
	// Lunar's published schema (Apollo mods/impl/ModWorldeditCui.java) has
	// enabled=false, Position One (blue), Position Two (red) and Outline (green);
	// those are Lunar's exactly. Show Grid / Grid Color / Line Thickness / See
	// Through are Origin additions in the same spirit (WorldEditCUI's own
	// renderer draws a grid and a dimmed see-through pass by default).
	// Protocol + drawing: worldedit/WorldEditCui + worldedit/CuiRenderer.
	private static void registerLunarBatch4b() {
		add("worldeditcui", "WorldEdit CUI", "See your WorldEdit selection in the world.", false,
				ModOption.toggle("grid", "Show Grid", true).tip("Draw a block grid across the selection's faces."),
				ModOption.color("gridColor", "Grid Color", 0x8000FF00).under("grid"),
				ModOption.slider("thickness", "Line Thickness", 1, 5, 1, 2, "%.0f"),
				ModOption.toggle("seeThrough", "See Through Blocks", true)
						.tip("Show the parts of the selection behind blocks, dimmed."),
				ModOption.header("Color"),
				ModOption.color("outlineColor", "Outline Color", 0xFF00FF00),
				ModOption.color("pos1Color", "Position One Color", 0xFF0000FF)
						.tip("The //pos1 corner (and a polygon's first point, or a sphere/cylinder's centre)."),
				ModOption.color("pos2Color", "Position Two Color", 0xFFFF0000)
						.tip("The //pos2 corner (and every later polygon point)."));
	}

	// ---- Lunar parity, batch 2: visuals / feel (2026-10-11) ----
	// Each mod's option set mirrors Lunar Client's own, read from Lunar's
	// published option schema (github.com/LunarClient/Apollo, mods/impl/Mod*.java).
	// Defaults are Lunar's too -- including ON for Boss Bar, Titles, Menu Blur and
	// Cooldowns -- except where a comment names a deliberate difference.
	// Kept in its own method so it merges cleanly with other registry additions.
	private static void registerLunarBatch2() {
		add("hurtcam", "Hurt Cam", "Tune or remove the camera shake when you take damage.", false,
				ModOption.toggle("disableHurtCam", "Disable Hurt Cam", false)
						.tip("Remove the damage shake completely."),
				ModOption.slider("intensity", "Hurt Shake Intensity", 0, 2, 0.05, 1.0, "%.2fx"),
				ModOption.toggle("oldTilt", "Old Camera Tilt", false)
						.tip("1.8-style: the camera always tilts the same way, whichever side the hit came from."));

		// Lunar's default tint is a darker red at 40% strength (vanilla's is 30%).
		add("hitcolor", "Hit Color", "Recolor the red damage flash on mobs and players.", false,
				ModOption.color("color", "Hit Color", 0x66990000)
						.tip("The colour a mob or player flashes when hurt. Its opacity is how strong the flash is."),
				ModOption.toggle("colorArmor", "Color Armor", true)
						.tip("Flash worn armor too, not just the body underneath."));

		// Two colours per target, like Lunar: one used with Lunar Blending on, one
		// with it off (vanilla's additive glint, which can only brighten).
		add("glintcolorizer", "Glint Colorizer", "Recolor the enchantment glint.", false,
				ModOption.toggle("showGlint", "Show Glint", true).tip("Turn off to hide the enchantment glint entirely."),
				ModOption.toggle("lunarBlending", "Lunar Blending", true)
						.tip("Blend the colour over the item instead of adding light, so dark colours (even black) show."),
				ModOption.header("Items"),
				ModOption.toggle("overrideItem", "Recolor Item Glint", true),
				ModOption.color("itemColorLunar", "Item Color (Lunar Blending)", 0xCC6419FF).under("overrideItem"),
				ModOption.color("itemColorVanilla", "Item Color (Vanilla Blending)", 0xFF8040CC).under("overrideItem"),
				ModOption.header("Armor"),
				ModOption.toggle("overrideArmor", "Recolor Armor Glint", true),
				ModOption.color("armorColorLunar", "Armor Color (Lunar Blending)", 0xCC6419FF).under("overrideArmor"),
				ModOption.color("armorColorVanilla", "Armor Color (Vanilla Blending)", 0xFF8040CC).under("overrideArmor"));

		add("bossbar", "Boss Bar", "Hide, move, resize or recolor the boss bar.", true,
				ModOption.slider("scale", "Scale", 0.5, 1.5, 0.05, 1.0, "%.2fx"),
				ModOption.toggle("renderBar", "Show Bar", true),
				ModOption.toggle("renderText", "Show Boss Name", true),
				ModOption.toggle("customColor", "Custom Bar Color", false),
				ModOption.color("barColor", "Bar Color", 0xFFF249F2).under("customColor"),
				ModOption.toggle("useGuiScale", "Use Minecraft GUI Scale", true)
						.tip("Off = the bar keeps one size whatever your GUI Scale is."));

		// Title Color defaults to WHITE here (Lunar's is red, 0xFFFF5555): it tints
		// only title text the server left uncoloured, which vanilla draws white, so
		// white keeps every title looking exactly like vanilla until you pick one.
		add("titles", "Titles", "Move, resize and recolor on-screen titles.", true,
				ModOption.slider("scale", "Scale", 0.5, 1.5, 0.05, 1.0, "%.2fx"),
				ModOption.toggle("showTitle", "Show Title", true),
				ModOption.toggle("showSubtitle", "Show Subtitle", true),
				ModOption.toggle("keepCentered", "Keep Title Centered", true)
						.tip("Centre titles on the element; off = they start at its left edge."),
				ModOption.toggle("showInHudEditor", "Show in HUD Editor", false)
						.tip("Show a sample title in Edit HUD so you can drag and resize it."),
				ModOption.toggle("useGuiScale", "Use Minecraft GUI Scale", true)
						.tip("Off = titles keep one size whatever your GUI Scale is."),
				ModOption.header("Color"),
				ModOption.color("titleColor", "Title Color", 0xFFFFFFFF)
						.tip("Colours title text the server didn't colour itself."),
				ModOption.color("subtitleColor", "Subtitle Color", 0xFFFFFFFF)
						.tip("Colours subtitle text the server didn't colour itself."));

		// "Origin Menus" defaults OFF (Lunar's own-menu toggle defaults on): the
		// mod menu is see-through on purpose so HUD changes preview live behind it.
		add("menublur", "Menu Blur", "Blur the world behind menus.", true,
				ModOption.slider("strength", "Blur Strength", 0, 20, 1, 4, "%.0f"),
				ModOption.toggle("inventory", "Inventories", true).tip("Blur behind your inventory, chests and every other container."),
				ModOption.color("inventoryBg", "Inventory Tint", 0x6F000000).under("inventory"),
				ModOption.toggle("pause", "Pause & Game Menus", true).tip("Blur behind the pause menu and the in-game option screens."),
				ModOption.color("pauseBg", "Pause Menu Tint", 0x6F000000).under("pause"),
				ModOption.toggle("originMenus", "Origin Menus", false).tip("Blur behind Origin's own mod menu."),
				ModOption.color("originMenusBg", "Origin Menu Tint", 0x00000000).under("originMenus"));

		add("shinypots", "Shinypots", "Give potions the full-box enchantment shine.", false,
				ModOption.toggle("coloredPotions", "Colored Potions", false).tip("Tint the shine with the potion's own colour."),
				ModOption.toggle("behind", "Render Glint Behind Potion", false),
				ModOption.toggle("entireSlot", "Render Entire Slot", false).tip("Shine the whole slot, not just the item's square."));

		add("tntcountdown", "TNT Countdown", "Show the fuse countdown above lit TNT.", false,
				ModOption.slider("fuseOffset", "Adjust Fuse Time", -80, 80, 1, 0, "%.0f ticks")
						.tip("Shift the countdown for servers that change the fuse length. 20 ticks = 1 second."),
				ModOption.toggle("background", "Background", true),
				ModOption.toggle("textShadow", "Text Shadow", false),
				ModOption.toggle("staticColor", "Static Countdown Color", false)
						.tip("Keep one colour the whole time instead of fading to red."),
				ModOption.header("Color"),
				ModOption.color("color", "Countdown Color", 0xFF00FF00),
				ModOption.color("prefixColor", "Prefix Color", 0xFFFFFFFF));

		// The key isn't in Lunar's schema (keybinds live outside it); V is free in
		// vanilla and nothing else in Origin uses it.
		add("snaplook", "Snaplook", "Hold a key to look behind you.", false,
				ModOption.keybind("key", "Snaplook Key", GLFW.GLFW_KEY_V),
				ModOption.toggle("toggleMode", "Toggle Mode", false).tip("Press once to look behind, press again to look forward."),
				ModOption.toggle("smoothCamera", "Smooth Camera Movement", true)
						.tip("Eases camera motion while you look behind you."));

		add("cooldowns", "Cooldowns", "Item cooldowns (pearls, shields...) on the HUD.", true,
				ModOption.slider("scale", "Scale", 0.25, 5.0, 0.05, 1.0, "%.2fx"),
				ModOption.header("Color"),
				ModOption.color("circleStart", "Circle Start Color", 0x99595959),
				ModOption.color("circleEnd", "Circle End Color", 0xFFE5E5E5),
				ModOption.color("edgeColor", "Edge Color", 0xFF404040),
				ModOption.color("textColor", "Text Color", 0xFFFFFFFF));

		add("soundchanger", "Sound Changer", "Change the volume of individual sounds.", false,
				SoundChanger.options());

		add("itemphysics", "Item Physics", "Dropped items tumble as they fall and lie flat.", false,
				ModOption.slider("rotationSpeed", "Rotation Speed", 0.1, 4.0, 0.1, 0.5, "%.1fx")
						.tip("How fast items spin while they're in the air."));
	}

	// ---- Lunar parity, batch 3b: server integrations (2026-10-11) ----
	// Team View and Hypixel mirror Lunar's option schemas (Apollo ModTeamView /
	// ModHypixelMod); Discord presence is always on and has no entry here. Team View and
	// Hypixel default ON like Lunar's, and each only acts where it applies (Team
	// View on team servers, Hypixel on *.hypixel.net).
	// Kept in its own method so it merges cleanly with other registry additions.
	private static void registerLunarBatch3b() {
		// Lunar's "Apollo Teams" row (its own server plugin) becomes Scoreboard
		// Teams: Origin can't speak Apollo, but every team server -- Hypixel included
		// -- puts teammates on one vanilla scoreboard team, which is the same answer.
		add("teamview", "Team View", "Marks where your teammates are, even through walls.", true,
				ModOption.toggle("scoreboardTeams", "Scoreboard Teams", true)
						.tip("Mark players on your scoreboard team (team games on any server)."),
				ModOption.toggle("hypixel", "Hypixel Parties", true)
						.tip("On Hypixel, also mark your party members while you're in a lobby or SkyBlock -- never in a game where it would be an unfair advantage."),
				ModOption.header("Color"),
				ModOption.color("teamColor", "Team Color", 0xFF2BFF41),
				ModOption.color("partyColor", "Party Color", 0xFFF6C634));

		// Discord Rich Presence is NOT a mod: it is always on (DiscordPresence, ticked
		// from ServerMods), so nothing is registered for it here.

		// Hypixel: Lunar's Hypixel mod. Everything acts only while connected to
		// *.hypixel.net. Levels and stats come from the Hypixel API with the player's
		// OWN key (stored only in this PC's Origin config, masked on screen, never
		// logged). Lunar's Auto GG row is the separate Auto GG mod in Origin; its
		// Guild MOTD / Hypixel Autocomplete rows have no reliable client-side signal
		// and are left out rather than shipped as switches that do nothing.
		add("hypixel", "Hypixel", "Hypixel levels, Bed Wars stats and chat tools.", true,
				ModOption.header("Hypixel API"),
				ModOption.text("apiKey", "API Key", "").secret()
						.tip("Your personal key from developer.hypixel.net. Click, then paste with Ctrl+V and press Enter. It stays on this PC and is only sent to Hypixel."),
				ModOption.header("Levels & Stats"),
				ModOption.toggle("levelHead", "Level Head", false)
						.tip("Show each player's Hypixel level on their nametag."),
				ModOption.toggle("levelAbove", "Level Above Name", false).under("levelHead")
						.tip("Put the level on its own line above the name instead of after it."),
				ModOption.color("levelColor", "Level Text Color", 0xFFFFFF55).under("levelHead"),
				ModOption.color("levelNumberColor", "Level Number Color", 0xFF55FFFF).under("levelHead"),
				ModOption.toggle("useBedwarsFormat", "Use Bed Wars Levels Format", true)
						.tip("In Bed Wars, show each player's Bed Wars star (e.g. [245✫]) instead of their network level."),
				ModOption.toggle("levelsInTab", "Show Levels in Tab", false)
						.tip("Show each player's level beside their name in the player list."),
				ModOption.toggle("bedwarsStats", "Bed Wars Stats in Tab", false)
						.tip("In Bed Wars, show each player's final K/D (FKDR) and win/loss ratio in the player list."),
				ModOption.header("Automation"),
				ModOption.toggle("autoFriend", "Auto Friend", false)
						.tip("Accept every friend request automatically."),
				ModOption.toggle("autoTip", "Auto Tip", false)
						.tip("Run /tip all every 15 minutes (tips the active boosters for free coins and XP)."),
				ModOption.toggle("autoWho", "Auto Who", false)
						.tip("Run /who when a game starts, so you see who's in it."),
				ModOption.toggle("antiGg", "Anti GG", false)
						.tip("Hide \"gg\" messages from other players at the end of a game."),
				ModOption.header("Tab & Chat"),
				ModOption.toggle("removeGuildOnTab", "Remove Guild Tags in Tab", true),
				ModOption.toggle("shortChannelNames", "Short Channel Names", false)
						.tip("\"Party >\" becomes \"P >\", \"Guild >\" becomes \"G >\", \"Officer >\" becomes \"O >\"."),
				ModOption.toggle("hidePrivateMessages", "Hide Private Messages", false),
				ModOption.toggle("hideTeamChat", "Hide Team Chat", false),
				ModOption.toggle("hidePartyChat", "Hide Party Chat", false),
				ModOption.toggle("hideGuildChat", "Hide Guild Chat", false),
				ModOption.toggle("hideShout", "Hide Shouts", false),
				ModOption.toggle("hideSpectatorChat", "Hide Spectator Chat", false),
				ModOption.toggle("removeLobbyStatuses", "Remove Lobby Join Messages", false)
						.tip("Hide \"[MVP+] Name joined the lobby!\" announcements."),
				ModOption.toggle("hideJoinMessages", "Hide Friend/Guild Join Messages", false),
				ModOption.toggle("hideLeaveMessages", "Hide Friend/Guild Leave Messages", false),
				ModOption.toggle("hideSoulWell", "Hide Soul Well Announcements", false),
				ModOption.toggle("hideMysteryBox", "Hide Mystery Box Announcements", false));
	}

	// ---- Lunar parity, batch 4a: Minimap (2026-10-11) ----
	// Lunar's own schema (Apollo mods/impl/ModMinimap.java) gives Scale, Map Width /
	// Height, Map Zoom, Rotate With Player, Fit Text To Width, Border (+colour,
	// thickness), Compass (+colour, shadow), Player Marker colour/size, Entity Marker
	// opacity/size/shadow, Show Coordinates / Biome (+Preset Biome Color) / Clock
	// (+AM/PM, Military Time) and Show Distant Waypoints, with Lunar's defaults (off;
	// blue player marker; 8x8). Lunar's minimap is Xaero's underneath, so the rows
	// Xaero has that the schema doesn't list are added the way Xaero ships them:
	// Shape, Cave Mode, Lighting, Terrain Shading, Biome Colors, Chunk Grid, the
	// per-type entity radar, Show Waypoints, zoom keys and the full-screen map key.
	// Map Zoom goes past Lunar's 1-2x range (0.5-4x) to cover Xaero's zoom levels.
	// Everything is read live by minimap/MinimapHud.
	private static void registerLunarBatch4a() {
		add("minimap", "Minimap", "A live map of the area around you. Singleplayer only.", false,
				ModOption.dropdown("shape", "Shape", "Square", "Circle"),
				ModOption.slider("scale", "Scale", 0.25, 5.0, 0.05, 1.0, "%.2fx"),
				ModOption.slider("mapWidth", "Map Width", 1, 16, 1, 8, "%.0f").tip("A round map uses Map Width for its size."),
				ModOption.slider("mapHeight", "Map Height", 1, 16, 1, 8, "%.0f"),
				ModOption.slider("zoom", "Map Zoom", 0.5, 4.0, 0.25, 1.0, "%.2fx")
						.tip("1x = one block per pixel. Higher zooms in, lower shows more area."),
				ModOption.keybind("zoomInKey", "Zoom In Key", -1),
				ModOption.keybind("zoomOutKey", "Zoom Out Key", -1),
				ModOption.toggle("rotate", "Rotate With Player", true).tip("Off = north is always up."),
				ModOption.keybind("openMapKey", "Open Full Map", GLFW.GLFW_KEY_M)
						.tip("A big map of everything mapped around you. Scroll to zoom, drag to move."),
				ModOption.header("Terrain"),
				ModOption.dropdown("caveMode", "Cave Mode", "Auto", "Off", "Always")
						.tip("Auto maps the cave you're in whenever there's a roof over your head (and always in the Nether)."),
				ModOption.toggle("lighting", "Lighting", true).tip("Darken the map at night; torches and other lights still glow."),
				ModOption.toggle("terrainShading", "Terrain Shading", true).tip("Shade slopes so hills and drops are easy to read."),
				ModOption.toggle("biomeColors", "Biome Colors", true).tip("Grass, leaves and water take their biome's colour."),
				ModOption.toggle("chunkGrid", "Chunk Grid", false),
				ModOption.color("gridColor", "Chunk Grid Color", 0x40FFFFFF).under("chunkGrid"),
				ModOption.header("Markers"),
				ModOption.color("playerMarkerColor", "Player Marker Color", 0xFF0000FF),
				ModOption.slider("playerMarkerSize", "Player Marker Size", 1, 10, 0.5, 5, "%.1f"),
				ModOption.toggle("showPlayers", "Show Players", true),
				ModOption.toggle("playerHeads", "Player Heads", true).under("showPlayers").tip("Show each player's face instead of a dot."),
				ModOption.color("playerColor", "Player Color", 0xFFFFFFFF).under("showPlayers"),
				ModOption.toggle("showHostile", "Show Hostile Mobs", true),
				ModOption.color("hostileColor", "Hostile Mob Color", 0xFFFF5555).under("showHostile"),
				ModOption.toggle("showPassive", "Show Passive Mobs", true),
				ModOption.color("passiveColor", "Passive Mob Color", 0xFF55FF55).under("showPassive"),
				ModOption.toggle("showItems", "Show Items", false),
				ModOption.color("itemColor", "Item Color", 0xFFFFD855).under("showItems"),
				ModOption.toggle("showOther", "Show Other Entities", false).tip("Boats, minecarts, arrows, XP orbs..."),
				ModOption.color("otherColor", "Other Entity Color", 0xFFB0B0B0).under("showOther"),
				ModOption.slider("entityMarkerSize", "Entity Marker Size", 1, 10, 0.5, 1, "%.1f"),
				ModOption.slider("entityMarkerOpacity", "Entity Marker Opacity", 0, 1, 0.05, 1.0, "%.0f%%"),
				ModOption.toggle("entityMarkerShadow", "Entity Marker Shadow", true),
				ModOption.toggle("showWaypoints", "Show Waypoints", true).tip("Your Waypoints mod waypoints, as diamonds in their colour."),
				ModOption.toggle("showDistantWaypoints", "Show Distant Waypoints", true)
						.under("showWaypoints").tip("Waypoints off the map sit on its edge, pointing the way."),
				ModOption.header("Info"),
				ModOption.toggle("showCoordinates", "Show Coordinates", true),
				ModOption.toggle("showBiome", "Show Biome", false),
				ModOption.toggle("presetBiomeColor", "Preset Biome Color", true).under("showBiome"),
				ModOption.toggle("showClock", "Show Clock", false).tip("The world's time of day."),
				ModOption.toggle("showAmPm", "Show AM/PM", true).under("showClock"),
				ModOption.toggle("militaryTime", "Military Time", false).under("showClock"),
				ModOption.toggle("fitTextToWidth", "Fit Text To Width", true).tip("Shrink long info lines to the map's width."),
				ModOption.header("Style"),
				ModOption.toggle("compass", "Compass", true),
				ModOption.color("compassColor", "Compass Color", 0xFFFFFFFF).under("compass"),
				ModOption.toggle("compassShadow", "Compass Shadow", true).under("compass"),
				ModOption.toggle("border", "Border", true),
				ModOption.color("borderColor", "Border Color", 0x9F000000).under("border"),
				ModOption.slider("borderThickness", "Border Thickness", 1, 5, 0.5, 1, "%.1f").under("border"),
				ModOption.toggle("showBackground", "Show Background", true).tip("Fills the parts of the map that aren't mapped yet."),
				ModOption.color("bgColor", "Background Color", 0x00000000).under("showBackground"));
	}

	// ---- Lunar parity, batch 3a: chat / server (2026-10-11) ----
	// Lunar's published schema (github.com/LunarClient/Apollo, mods/impl/Mod*.java)
	// only lists the server-controllable switches of these mods; the text fields,
	// delays and keys below are the obvious set a Lunar player expects and are
	// marked "inferred" in the batch notes. Defaults follow Lunar's where it
	// publishes one (Nick Hider's hide-name/own-skin ON, Screenshot ON).
	// Kept in its own method so it merges cleanly with other registry additions.
	private static void registerLunarBatch3a() {
		add("autotext", "Auto Text Hotkey", "Send a chat message or command with one key.", false,
				AutoTextHotkey.options());

		// Lunar keeps Auto GG inside its Hypixel mod; here it's its own card.
		add("autogg", "Auto GG", "Say gg automatically when a game ends.", false,
				ModOption.text("message", "Message", "gg")
						.tip("Sent to all chat the moment a Hypixel game ends."),
				ModOption.slider("delay", "Delay", 0, 5, 0.5, 1, "%.1fs")
						.tip("Wait this long after the game ends before sending."),
				ModOption.toggle("secondMessage", "Second Message", false)
						.tip("Send a second line one second after the first."),
				ModOption.text("secondText", "Second Message Text", "Have a good day!").under("secondMessage"),
				// Anti GG lives in the Hypixel mod (batch 3b), next to Lunar's own row.
				ModOption.header("Chat"),
				ModOption.toggle("antiKarma", "Hide Karma Messages", false)
						.tip("Hide Hypixel's \"+5 Karma!\" lines."));

		// Will (2026-10-12): only the "hide it from my own screen" rows. Everything
		// here changes what YOU see -- other players still see your real name -- so
		// the nick-workaround rows (suffix, real name, real skin) and the hide-
		// everyone-else rows were cut as looking like cheats.
		add("nickhider", "Nick Hider", "Hide your name and skin on your own screen (for streaming).", false,
				ModOption.toggle("hideName", "Hide Your Name", true)
						.tip("You see this name instead of yours in chat, tab, nametags and the scoreboard. Only on your screen -- other players still see your real name."),
				ModOption.text("name", "Your Name", "You").under("hideName"),
				ModOption.toggle("hideLobbyId", "Hide Lobby ID", false)
						.tip("Hide the server ID Hypixel shows next to the date on the scoreboard, so stream viewers can't find your lobby."),
				ModOption.header("Skins"),
				ModOption.toggle("hideOwnSkin", "Hide Your Skin", true)
						.tip("You see yourself in the default skin. Only on your screen."));

		// G is unbound in vanilla 1.21.1 and unused elsewhere in Origin.
		add("quickplay", "Quickplay", "Jump into any Hypixel game from one menu.", false,
				ModOption.keybind("key", "Open Quickplay", GLFW.GLFW_KEY_G),
				ModOption.keybind("requeueKey", "Play Again", -1)
						.tip("Re-joins the last game you picked in Quickplay."),
				ModOption.toggle("displayMessage", "Display Queue Message", true)
						.tip("Show which game you're joining above the hotbar."));

		// Will's design (2026-10-11): nothing is uploaded. Lunar's Upload / Tweet
		// buttons are replaced by local actions -- copy the image, copy the file,
		// open the folder. Since 2026-10-12 they're buttons on Minecraft's own
		// "Saved screenshot" chat line instead of a pop-up (Will: less in the way).
		add("screenshot", "Screenshot", "Copy or find each screenshot right from chat.", true,
				ModOption.toggle("copyAutomatically", "Copy Automatically", false)
						.tip("Put every new screenshot on the clipboard straight away."),
				ModOption.keybind("copyKey", "Copy Last Screenshot", -1)
						.tip("Copies the most recent screenshot's image to the clipboard."),
				ModOption.header("Chat Buttons"),
				ModOption.toggle("copyImageButton", "Copy Image", true)
						.tip("Copies the picture itself -- paste it straight into Discord. Open chat (T) to click."),
				ModOption.toggle("copyFileButton", "Copy File", true)
						.tip("Copies the file, like Ctrl+C in File Explorer. Open chat (T) to click."),
				ModOption.toggle("openFolderButton", "Open Folder", true)
						.tip("Opens your screenshots folder. Open chat (T) to click."));
	}

	// ---- Origin originals: FPS Coach (2026-10-12) ----
	// Origin's own mod (no other client has it): watches frame time while you
	// play and, after ~20 s below the target, offers measured setting tips
	// (mods/FpsCoach, mods/FpsCoachTips, gui/FpsCoachScreen). The mod card opens
	// the coach itself (OriginModMenuScreen.openMod); its Settings button lands
	// here. Default ON, like the other quiet background helpers that only speak
	// up when useful (Screenshot buttons, Saturation, Menu Blur); it stays silent
	// until FPS is actually low. Category: Gameplay (the menu's default bucket
	// for tools, next to Quickplay and Screenshot), so no CATS entry is needed.
	// "Reset Hidden Tips" is a switch that acts as a button: FpsCoach.tick
	// brings every hidden tip back and switches it off again.
	private static void registerOriginalsFpsCoach() {
		add("fpscoach", "FPS Coach", "Finds the settings costing you FPS and tests each fix.", true,
				ModOption.slider("targetFps", "Target FPS", 30, 240, 10, 60, "%.0f")
						.tip("Tips show up after you stay below this for about 20 seconds."),
				ModOption.keybind("key", "Open FPS Coach", GLFW.GLFW_KEY_K),
				ModOption.toggle("showToast", "Show Tips Pop-up", true)
						.tip("A small pop-up when there are tips. Never during a fight or with a menu open."),
				ModOption.slider("cooldown", "Pop-up Cooldown", 1, 60, 1, 10, "%.0f min").under("showToast")
						.tip("The shortest time between two pop-ups."),
				ModOption.toggle("resetHidden", "Reset Hidden Tips", false)
						.tip("Brings back every tip you hid with \"Hide\". Switches itself back off."));
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

	// ---- Lunar parity: Hypixel Bed Wars (2026-10-11) ----
	// Lunar's separate "Hypixel Bedwars" mod. The first nine rows are Lunar's
	// published schema exactly (Apollo mods/impl/ModHypixelBedwars.java), with
	// Lunar's defaults: mod ON, Colored Beds ON, Hardcore Hearts ON, the rest off,
	// both alert colours white. Resource Counter and Teammates are the two HUDs
	// Lunar's own posts list with the mod (Patch Notes #15, May 2025 recap); Lunar
	// doesn't publish their defaults, so they start off. Everything acts only in a
	// Bed Wars game on *.hypixel.net (mods/HypixelBedwars, hud/BedwarsHud).
	// Kept in its own method so it merges cleanly with other registry additions.
	private static void registerLunarBedwars() {
		add("hypixelbedwars", "Hypixel Bed Wars", "Team-coloured beds, hardcore hearts and trap alerts in Bed Wars.", true,
				ModOption.toggle("coloredBeds", "Colored Beds", true)
						.tip("Recolor the beds in-game according to the team's color."),
				ModOption.toggle("practiceColor", "Enable Practice Color", false).under("coloredBeds")
						.tip("Recolor the beds in Bed Wars practice mode too."),
				ModOption.toggle("hardcoreHearts", "Hardcore Hearts", true)
						.tip("Replace normal hearts with hardcore hearts once your bed is lost."),
				ModOption.toggle("hideFoodBar", "Hide Food Bar", false)
						.tip("Hunger is off in Bed Wars, so the food bar never changes."),
				ModOption.toggle("hideArmorBar", "Hide Armor Bar", false),
				ModOption.header("Trap Alerts"),
				ModOption.toggle("customTrapAlert", "Custom Trap Alert", false)
						.tip("Show the \"TRAP TRIGGERED!\" alert in your own colors."),
				ModOption.color("alertPrimaryColor", "Alert Primary Color", 0xFFFFFFFF).under("customTrapAlert"),
				ModOption.color("alertSubColor", "Alert Sub Color", 0xFFFFFFFF).under("customTrapAlert"),
				ModOption.toggle("muteAlertSound", "Mute Alert Sound", false)
						.tip("Silence the sound Hypixel plays when one of your traps goes off."),
				ModOption.header("HUD"),
				ModOption.toggle("resourceCounter", "Resource Counter", false)
						.tip("How much iron, gold, diamond and emerald you're carrying."),
				ModOption.toggle("teammatesHud", "Teammates", false)
						.tip("Your teammates' health, and who's out, on screen all game."),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.toggle("border", "Border", false),
				ModOption.color("borderColor", "Border Color", 0x66FFFFFF).under("border"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF),
				ModOption.color("bgColor", "Background Color", 0x00000000));
	}

	// ---- Origin originals: Portal Calculator (2026-10-12) ----
	// Origin's own mod -- no other client has one, so there is no Lunar schema to
	// mirror; the rows are Will's brief. Off by default, like every utility mod
	// with no Lunar default (Waypoints too). The mark key is unbound by default,
	// as the Waypoints keys are. Logic: PortalCalc + PortalMemory; HUD:
	// hud/PortalCalcHud. Kept in its own method so it merges cleanly.
	private static void registerOriginalsPortalCalc() {
		add(PortalCalc.ID, "Portal Calculator", "Where your nether portal leads, and where to build its match.", false,
				ModOption.toggle("showHud", "Show HUD Readout", true)
						.tip("Show the spot in the other dimension that matches where you stand."),
				ModOption.toggle("showLinkCheck", "Show Link Check", true)
						.tip("At a nether portal, show which of your known portals it will take you to."),
				ModOption.toggle("rememberPortals", "Remember Portals", true)
						.tip("Remember every nether portal you stand in or next to, for each world. The link check uses them."),
				ModOption.toggle("autoWaypoint", "Auto Waypoint For New Portals", false).under("rememberPortals")
						.tip("The first time you step into a portal, mark its matching spot in the other dimension."),
				ModOption.toggle("decimal", "Decimal Coordinates", false)
						.tip("Show the matching spot to one decimal place."),
				ModOption.keybind("markKey", "Mark Matching Spot", -1)
						.tip("Drops a \"Portal link\" waypoint at the matching spot in the other dimension."),
				ModOption.toggle("textShadow", "Text Shadow", true),
				ModOption.toggle("showBackground", "Show Background", true),
				ModOption.toggle("border", "Border", false),
				ModOption.color("borderColor", "Border Color", 0x66FFFFFF).under("border"),
				ModOption.header("Color"),
				ModOption.color("color", "Text Color", 0xFFFFFFFF),
				ModOption.color("accentColor", "Accent Color", 0xFFB57CFF)
						.tip("The dimension name, the tick, and the colour of Portal link waypoints."),
				ModOption.color("warnColor", "Warning Color", 0xFFFFC857)
						.tip("Used when a portal won't link the way you'd expect."),
				ModOption.color("bgColor", "Background Color", 0x00000000));
	}

	// The shared text-readout block every Lunar-parity readout carries after its
	// own rows: Text Shadow / Brackets / Background / Border plus their colours,
	// keyed the way the FPS/CPS readouts are (color, bgColor, ...) so the HUD's
	// one drawing helper (Readouts.textBox) reads them identically for every mod.
	private static ModOption[] textReadout(ModOption... own) {
		List<ModOption> o = new ArrayList<>(List.of(own));
		o.add(ModOption.toggle("textShadow", "Text Shadow", true));
		o.add(ModOption.toggle("showBrackets", "Show Brackets", false));
		o.add(ModOption.color("bracketColor", "Bracket Color", 0xFFFFFFFF).under("showBrackets"));
		o.add(ModOption.toggle("showBackground", "Show Background", true));
		o.add(ModOption.toggle("border", "Border", false));
		o.add(ModOption.color("borderColor", "Border Color", 0x66FFFFFF).under("border"));
		o.add(ModOption.header("Color"));
		o.add(ModOption.color("color", "Text Color", 0xFFFFFFFF));
		o.add(ModOption.color("bgColor", "Background Color", 0x00000000));
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

	// O(1) lookup indexes, built once lazily. ALL and the two settings lists are
	// fully populated at class-init and never mutated after, so a cached index is
	// always valid. This matters a lot: the HUD reads hundreds of config options
	// every frame, and every DEFAULT-valued read used to fall through to a linear
	// scan over all mods (byId) + all of that mod's options (opt) — thousands of
	// String.equals per frame at idle. Both are now HashMap gets. (Perf audit #1.)
	private static volatile Map<String, Mod> BY_ID;
	private static volatile Map<String, Map<String, ModOption>> OPT_IDX;

	public static Mod byId(String id) {
		Map<String, Mod> idx = BY_ID;
		if (idx == null) {
			synchronized (Mods.class) {
				idx = BY_ID;
				if (idx == null) {
					idx = new java.util.HashMap<>();
					for (Mod m : ALL) {
						idx.put(m.id(), m);
					}
					BY_ID = idx;
				}
			}
		}
		return idx.get(id);
	}

	/** The (key -> ModOption) map for a mod id or the GENERAL/PERFORMANCE pseudo-id;
	 *  null for an unknown id. Backs {@link #opt} with an O(1) get. */
	private static Map<String, ModOption> optsFor(String modId) {
		Map<String, Map<String, ModOption>> idx = OPT_IDX;
		if (idx == null) {
			synchronized (Mods.class) {
				idx = OPT_IDX;
				if (idx == null) {
					idx = new java.util.HashMap<>();
					for (Mod m : ALL) {
						idx.put(m.id(), indexOptions(m.options()));
					}
					// Pseudo-ids: the SETTINGS tab stores General/Performance options
					// under ids that aren't in ALL — index them the same way so their
					// schema defaults resolve (e.g. Entity Distance -> 100, not 0).
					idx.put(GENERAL_ID, indexOptions(GENERAL_SETTINGS));
					idx.put(PERFORMANCE_ID, indexOptions(PERFORMANCE_SETTINGS));
					OPT_IDX = idx;
				}
			}
		}
		return idx.get(modId);
	}

	private static Map<String, ModOption> indexOptions(List<ModOption> opts) {
		Map<String, ModOption> m = new java.util.HashMap<>(opts.size() * 2);
		for (ModOption o : opts) {
			m.put(o.key, o);
		}
		return m;
	}

	// ---- typed access (all read-through to ModsConfig with schema defaults) ----

	public static boolean on(String modId) {
		var vals = raw(modId);
		var v = vals.get("enabled");
		boolean enabled;
		if (v != null) {
			enabled = v.getAsBoolean();
		} else {
			Mod m = byId(modId);
			enabled = m != null && m.defaultOn();
		}
		// Origin steps aside when a player's own mod does this job (StepAside). This is
		// the one gate every mixin, hook and keybind of the feature already reads, so a
		// stepped-aside feature is off everywhere at once. Nothing replaced = the old value.
		if (!StepAside.any()) {
			return enabled;
		}
		return StepAside.effectiveOn(modId, enabled, forcedOn(vals));
	}

	public static void setOn(String modId, boolean on) {
		var vals = raw(modId);
		if (StepAside.replaced(modId) != null) {
			// A replaced feature's switch sets/clears the player's "keep Origin's on" flag.
			StepAside.Write w = StepAside.toggle(modId, on);
			if (w.forced()) {
				vals.put(StepAside.FORCED_KEY, new JsonPrimitive(true));
			} else {
				vals.remove(StepAside.FORCED_KEY);
			}
			if (w.enabled() != null) {
				vals.put("enabled", new JsonPrimitive(w.enabled()));
			}
			ModsConfig.save();
			return;
		}
		vals.put("enabled", new JsonPrimitive(on));
		ModsConfig.save();
	}

	/** True once the player turned this feature back on over the mod that replaced it (StepAside). */
	public static boolean forcedOn(String modId) {
		return forcedOn(raw(modId));
	}

	private static boolean forcedOn(Map<String, com.google.gson.JsonElement> vals) {
		var f = vals.get(StepAside.FORCED_KEY);
		try {
			return f != null && f.getAsBoolean();
		} catch (RuntimeException e) {
			return false;   // a hand-edited non-boolean value reads as "not forced"
		}
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
		if (o != null && o.kind == ModOption.Kind.MULTISELECT && o.defText != null) {
			return o.defText;
		}
		return o == null || o.modes == null || o.modes.length == 0 ? "" : o.modes[0];
	}

	/** A TEXT option's value; its schema default ("" if none) until set. */
	public static String text(String modId, String key) {
		var v = raw(modId).get(key);
		if (v != null) {
			try {
				return v.getAsString();
			} catch (RuntimeException e) {
				// a non-string landed here (hand-edited config): fall back to the default
			}
		}
		ModOption o = opt(modId, key);
		return o == null || o.defText == null ? "" : o.defText;
	}

	public static void set(String modId, String key, boolean v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	public static void set(String modId, String key, double v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	// Slider drags only: stores the value in memory WITHOUT writing the config
	// file. A drag fires on every mouse move, and set() rewrites the whole JSON
	// synchronously each call; the mod menu calls save() once on release.
	public static void setUnsaved(String modId, String key, double v) {
		raw(modId).put(key, new JsonPrimitive(v));
	}

	public static void save() {
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

	/** Back to the schema default: drops the stored value (and a colour's chroma
	 *  extras) so every reader falls through to the ModOption default. The mod
	 *  menu's reset arrow. */
	public static void reset(String modId, String key) {
		var m = raw(modId);
		m.remove(key);
		m.remove(key + "#chroma");
		m.remove(key + "#speed");
		m.remove(key + "#type");
		ModsConfig.save();
	}

	public static String metaText(String key, String def) {
		ModsConfig.ensureLoaded();
		var v = ModsConfig.META.get(key);
		try {
			return v == null ? def : v.getAsString();
		} catch (RuntimeException e) {
			return def;
		}
	}

	public static void setMetaText(String key, String v) {
		ModsConfig.ensureLoaded();
		if (v == null) {
			ModsConfig.META.remove(key);
		} else {
			ModsConfig.META.put(key, new JsonPrimitive(v));
		}
		ModsConfig.save();
	}

	/** Moves whenever the config is saved or reloaded (see ModsConfig.rev). */
	public static int configRev() {
		return ModsConfig.rev;
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

	public static double metaNum(String key, double def) {
		ModsConfig.ensureLoaded();
		var v = ModsConfig.META.get(key);
		try {
			return v == null ? def : v.getAsDouble();
		} catch (RuntimeException e) {
			return def;
		}
	}

	public static void setMetaNum(String key, double v) {
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
		return ModsConfig.VALUES.computeIfAbsent(modId, k -> new java.util.HashMap<>());
	}

	private static ModOption opt(String modId, String key) {
		// O(1) via the option index (see optsFor). The pseudo-id handling for the
		// SETTINGS tab (General/Performance) is baked into the index so their schema
		// defaults resolve (e.g. Entity Distance -> 100, not 0 = cull everything).
		Map<String, ModOption> opts = optsFor(modId);
		return opts == null ? null : opts.get(key);
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
		ModsConfig.VALUES.computeIfAbsent("zoom", k -> new java.util.HashMap<>())
				.put("fov", new JsonPrimitive(legacy.zoomFov));
	}

	private static void seed(String id, boolean on) {
		ModsConfig.VALUES.computeIfAbsent(id, k -> new java.util.HashMap<>())
				.put("enabled", new JsonPrimitive(on));
	}
}
