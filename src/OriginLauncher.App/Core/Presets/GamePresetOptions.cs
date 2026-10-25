namespace OriginLauncher.App.Core.Presets;

/// <summary>
/// The key/value bundle of each 1.8.9 preset across options.txt, optionsof.txt and Origin's own config.
/// ONE table (<see cref="Rows"/>); everything else (Preview, Apply, the label, the bench JSON files) reads it.
///
/// Starting point: docs/overhaul/audit/perf-189.md section 7. The numbers get tuned by U6.3's measurements.
/// Only OptiFine keys listed in tools/bench/optifine-keys-189.txt may appear (verified on the real HD_U_M5 jar by
/// U6.0, bench-189 CI run 40182245060); GamePresetOptionsTests fails on any other of* key.
/// </summary>
public static class GamePresetOptions
{
    public const string SupportedVersion = "1.8.9";

    // One table row = one key across the three presets. A null cell means that preset leaves the key alone.
    // Row order is the order lines are appended in a file that lacks them.
    private static GamePresetEntry?[] Row(GamePresetFile file, string key, string? competitive, string? balanced, string? quality,
        string? @default = null) =>
        new[]
        {
            competitive is null ? null : new GamePresetEntry(file, key, competitive, @default),
            balanced is null ? null : new GamePresetEntry(file, key, balanced, @default),
            quality is null ? null : new GamePresetEntry(file, key, quality, @default),
        };

    private static readonly GamePresetEntry?[][] Rows =
    {
        // ---- options.txt (vanilla 1.8.9 GameSettings) ----
        // Fast graphics for Competitive and Balanced; render distance is the biggest single FPS lever.
        Row(GamePresetFile.Options, "renderDistance",  "8",     "10",    "14"),
        Row(GamePresetFile.Options, "fancyGraphics",   "false", "false", "true"),
        // ao: 0 off, 1 minimum, 2 maximum.
        Row(GamePresetFile.Options, "ao",              "0",     "1",     "2"),
        // particles: 0 all, 1 decreased, 2 minimal. Competitive keeps "decreased" so crits stay visible.
        Row(GamePresetFile.Options, "particles",       "1",     "1",     "0"),
        Row(GamePresetFile.Options, "renderClouds",    "false", "true",  "true"),
        Row(GamePresetFile.Options, "entityShadows",   "false", "true",  "true"),
        Row(GamePresetFile.Options, "mipmapLevels",    "0",     "2",     "4"),

        // ---- optionsof.txt (OptiFine HD_U_M5; every key verified) ----
        // Fast Render stays OFF in every preset for now: it renders the frame without Minecraft's own
        // framebuffer, so its interplay with Origin's MenuBlur (reads that framebuffer), ColorGrade and
        // MotionBlur has not been render-checked yet (perf-189.md section 7, blocker 3). The row is kept, set to
        // false, so applying a preset also turns off a Fast Render the player had on. U6.3 flips Competitive
        // once the render check passes.
        Row(GamePresetFile.OptiFine, "ofFastRender",       "false", "false", "false"),
        Row(GamePresetFile.OptiFine, "ofFastMath",         "true",  "true",  "false"),
        Row(GamePresetFile.OptiFine, "ofChunkUpdates",     "1",     "1",     "2"),
        Row(GamePresetFile.OptiFine, "ofSmoothFps",        "false", "false", "false"),
        Row(GamePresetFile.OptiFine, "ofLazyChunkLoading", "true",  "true",  "false"),
        Row(GamePresetFile.OptiFine, "ofSmartAnimations",  "true",  "true",  "false"),
        // ofDynamicLights: 1 fast, 2 fancy, 3 off.
        Row(GamePresetFile.OptiFine, "ofDynamicLights",    "3",     "3",     "1"),
        // ofClouds: 0 default (follows fancy/fast graphics), 1 fast, 2 fancy, 3 off.
        Row(GamePresetFile.OptiFine, "ofClouds",           "3",     "1",     "0"),
        Row(GamePresetFile.OptiFine, "ofSky",              "false", "true",  "true"),
        Row(GamePresetFile.OptiFine, "ofStars",            "false", "true",  "true"),
        Row(GamePresetFile.OptiFine, "ofSunMoon",          "true",  "true",  "true"),
        // ofTrees: 0 default, 1 fast, 2 fancy, 3 smart.
        Row(GamePresetFile.OptiFine, "ofTrees",            "1",     "1",     "2"),
        // ofFogType: 1 fast, 2 fancy, 3 off.
        Row(GamePresetFile.OptiFine, "ofFogType",          "3",     "1",     "2"),
        // ofVignette: 0 default, 1 fast, 2 fancy. There is no "off" value: Fast is the setting that draws no
        // vignette (a value above 2 is out of range and may clamp to Fancy).
        Row(GamePresetFile.OptiFine, "ofVignette",         "1",     "1",     "0"),
        // ofAnimatedWater / Lava: 0 default, 1 fast, 2 off.
        Row(GamePresetFile.OptiFine, "ofAnimatedWater",    "2",     "1",     "0"),
        Row(GamePresetFile.OptiFine, "ofAnimatedLava",     "2",     "1",     "0"),
        Row(GamePresetFile.OptiFine, "ofAnimatedPortal",   "false", "true",  "true"),
        Row(GamePresetFile.OptiFine, "ofAnimatedRedstone", "false", "true",  "true"),
        Row(GamePresetFile.OptiFine, "ofAnimatedFlame",    "false", "true",  "true"),
        Row(GamePresetFile.OptiFine, "ofAnimatedSmoke",    "false", "true",  "true"),
        Row(GamePresetFile.OptiFine, "ofAnimatedTerrain",  "false", "true",  "true"),
        Row(GamePresetFile.OptiFine, "ofAnimatedTextures", "false", "true",  "true"),
        // Particle groups: Competitive drops the decorative ones; potion effects stay (they carry information).
        Row(GamePresetFile.OptiFine, "ofVoidParticles",     "false", "true", "true"),
        Row(GamePresetFile.OptiFine, "ofWaterParticles",    "false", "true", "true"),
        Row(GamePresetFile.OptiFine, "ofPortalParticles",   "false", "true", "true"),
        Row(GamePresetFile.OptiFine, "ofPotionParticles",   "true",  "true", "true"),
        Row(GamePresetFile.OptiFine, "ofFireworkParticles", "false", "true", "true"),
        Row(GamePresetFile.OptiFine, "ofDrippingWaterLava", "false", "true", "true"),
        Row(GamePresetFile.OptiFine, "ofRainSplash",        "false", "true", "true"),
        // ofBetterGrass / ofConnectedTextures: 1 fast, 2 fancy, 3 off.
        Row(GamePresetFile.OptiFine, "ofBetterGrass",       "3",     "3",    "2"),
        Row(GamePresetFile.OptiFine, "ofConnectedTextures", "1",     "1",    "2"),
        Row(GamePresetFile.OptiFine, "ofAfLevel",           "1",     "1",    "4"),
        // ---- Origin's own 1.8.9 toggles (config/originclient-mods.json; ids and defaults from Mods.java) ----
        // Cosmetic sparkles: Mods.GENERAL_SETTINGS "cosmeticEffects".
        Row(GamePresetFile.Origin, "@general.cosmeticEffects",        "false", "true", "true", "true"),
        // Entity / Tile Entity Distance, percent of the render distance (100 = off): Mods.PERFORMANCE_SETTINGS.
        Row(GamePresetFile.Origin, "@performance.entityDistance",     "75",    "100",  "100",  "100"),
        Row(GamePresetFile.Origin, "@performance.tileEntityDistance", "50",    "100",  "100",  "100"),
        // Menu Blur. Inventory blur is off in Competitive, on in Balanced and Quality (orchestrator decision,
        // phase-6 U6.2); the pause menu follows it.
        Row(GamePresetFile.Origin, "menublur.inventory",              "false", "true", "true", "true"),
        Row(GamePresetFile.Origin, "menublur.pause",                  "false", "true", "true", "true"),
        // Particle Changer. Its "Reduced" mode also drops crit particles, so no preset uses it (the vanilla
        // "decreased" above keeps crits); Competitive hides block-break particles instead, which carry no
        // information. Every preset writes the changer's four keys (enabled, hideBlockBreak, hideAll, mode), so
        // switching presets in any order ends in the same state: Balanced and Quality turn the changer on but
        // with everything pass-through (all particles shown), which is how it behaves with no changer at all.
        Row(GamePresetFile.Origin, "particles.enabled",               "true",  "true",  "true",  "false"),
        Row(GamePresetFile.Origin, "particles.hideBlockBreak",        "true",  "false", "false", "false"),
        Row(GamePresetFile.Origin, "particles.hideAll",               "false", "false", "false", "false"),
        Row(GamePresetFile.Origin, "particles.mode",                  "All",   "All",   "All",   "All"),
    };

    private static readonly Dictionary<GamePresetKind, GamePresetEntry[]> Bundles = Build();

    private static Dictionary<GamePresetKind, GamePresetEntry[]> Build()
    {
        var bundles = new Dictionary<GamePresetKind, GamePresetEntry[]>();
        foreach (var kind in Enum.GetValues<GamePresetKind>())
            bundles[kind] = Rows.Select(row => row[(int)kind]).OfType<GamePresetEntry>().ToArray();
        return bundles;
    }

    public static bool IsSupported(string version) => string.Equals(version, SupportedVersion, StringComparison.Ordinal);

    /// <summary>
    /// The settings of one preset. Only 1.8.9 has a bundle: any other version throws, because a bundle for a
    /// version nobody verified would write keys that version may not read.
    /// </summary>
    public static IReadOnlyList<GamePresetEntry> For(GamePresetKind kind, string version)
    {
        if (!IsSupported(version))
            throw new ArgumentOutOfRangeException(nameof(version), version, "Presets exist for 1.8.9 only.");
        return Bundles[kind];
    }

    /// <summary>What the label shows when the files match none of the presets.</summary>
    public const string CustomLabel = "Custom";

    // The bench JSON (tools/bench/presets-189/*.json): {"options": {...}, "optionsof": {...}, "origin": {...}}. Every
    // value is a STRING because prod189.py's apply_preset writes "%s:%s" itself (a JSON true would print as "True").
    // apply_preset ignores the "origin" block (it only seeds options.txt and optionsof.txt). GamePresetFilesTests keeps
    // the checked-in files equal to this and rewrites them when ORIGIN_REGEN_PRESETS=1.
    public static string ToBenchJson(GamePresetKind kind)
    {
        var entries = For(kind, SupportedVersion);
        var sb = new System.Text.StringBuilder("{\n");
        var sections = new[] { (GamePresetFile.Options, "options"), (GamePresetFile.OptiFine, "optionsof"), (GamePresetFile.Origin, "origin") };
        for (var s = 0; s < sections.Length; s++)
        {
            var (file, name) = sections[s];
            var inSection = entries.Where(e => e.File == file).ToArray();
            sb.Append("  \"").Append(name).Append("\": {");
            for (var i = 0; i < inSection.Length; i++)
            {
                sb.Append(i == 0 ? "\n" : ",\n");
                sb.Append("    \"").Append(inSection[i].Key).Append("\": \"").Append(inSection[i].Value).Append('"');
            }
            sb.Append(inSection.Length == 0 ? "}" : "\n  }");
            sb.Append(s < sections.Length - 1 ? ",\n" : "\n");
        }
        return sb.Append("}\n").ToString();
    }
}
