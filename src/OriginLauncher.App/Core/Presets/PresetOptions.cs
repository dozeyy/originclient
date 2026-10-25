namespace OriginLauncher.App.Core.Presets;

/// <summary>
/// The key/value bundle of each 1.8.9 preset across options.txt, optionsof.txt and Origin's own config.
/// ONE table (<see cref="Rows"/>); everything else (Preview, Apply, the label, the bench JSON files) reads it.
///
/// Starting point: docs/overhaul/audit/perf-189.md section 7. The numbers get tuned by U6.3's measurements.
/// Only OptiFine keys listed in tools/bench/optifine-keys-189.txt may appear (verified on the real HD_U_M5 jar by
/// U6.0, bench-189 CI run 40182245060); PresetOptionsTests fails on any other of* key.
/// </summary>
public static class PresetOptions
{
    public const string SupportedVersion = "1.8.9";

    // One table row = one key across the three presets. A null cell means that preset leaves the key alone.
    // Row order is the order lines are appended in a file that lacks them.
    private static PresetEntry?[] Row(PresetFile file, string key, string? competitive, string? balanced, string? quality,
        string? @default = null) =>
        new[]
        {
            competitive is null ? null : new PresetEntry(file, key, competitive, @default),
            balanced is null ? null : new PresetEntry(file, key, balanced, @default),
            quality is null ? null : new PresetEntry(file, key, quality, @default),
        };

    private static readonly PresetEntry?[][] Rows =
    {
        // ---- options.txt (vanilla 1.8.9 GameSettings) ----
        // Fast graphics for Competitive and Balanced; render distance is the biggest single FPS lever.
        Row(PresetFile.Options, "renderDistance",  "8",     "10",    "14"),
        Row(PresetFile.Options, "fancyGraphics",   "false", "false", "true"),
        // ao: 0 off, 1 minimum, 2 maximum.
        Row(PresetFile.Options, "ao",              "0",     "1",     "2"),
        // particles: 0 all, 1 decreased, 2 minimal. Competitive keeps "decreased" so crits stay visible.
        Row(PresetFile.Options, "particles",       "1",     "1",     "0"),
        Row(PresetFile.Options, "renderClouds",    "false", "true",  "true"),
        Row(PresetFile.Options, "entityShadows",   "false", "true",  "true"),
        Row(PresetFile.Options, "mipmapLevels",    "0",     "2",     "4"),

        // ---- optionsof.txt (OptiFine HD_U_M5; every key verified) ----
        // Fast Render stays OFF in every preset for now: it renders the frame without Minecraft's own
        // framebuffer, so its interplay with Origin's MenuBlur (reads that framebuffer), ColorGrade and
        // MotionBlur has not been render-checked yet (perf-189.md section 7, blocker 3). The row is kept, set to
        // false, so applying a preset also turns off a Fast Render the player had on. U6.3 flips Competitive
        // once the render check passes.
        Row(PresetFile.OptiFine, "ofFastRender",       "false", "false", "false"),
        Row(PresetFile.OptiFine, "ofFastMath",         "true",  "true",  "false"),
        Row(PresetFile.OptiFine, "ofChunkUpdates",     "1",     "1",     "2"),
        Row(PresetFile.OptiFine, "ofSmoothFps",        "false", "false", "false"),
        Row(PresetFile.OptiFine, "ofLazyChunkLoading", "true",  "true",  "false"),
        Row(PresetFile.OptiFine, "ofSmartAnimations",  "true",  "true",  "false"),
        // ofDynamicLights: 1 fast, 2 fancy, 3 off.
        Row(PresetFile.OptiFine, "ofDynamicLights",    "3",     "3",     "1"),
        // ofClouds: 0 default (follows fancy/fast graphics), 1 fast, 2 fancy, 3 off.
        Row(PresetFile.OptiFine, "ofClouds",           "3",     "1",     "0"),
        Row(PresetFile.OptiFine, "ofSky",              "false", "true",  "true"),
        Row(PresetFile.OptiFine, "ofStars",            "false", "true",  "true"),
        Row(PresetFile.OptiFine, "ofSunMoon",          "true",  "true",  "true"),
        // ofTrees: 0 default, 1 fast, 2 fancy, 3 smart.
        Row(PresetFile.OptiFine, "ofTrees",            "1",     "1",     "2"),
        // ofFogType: 1 fast, 2 fancy, 3 off.
        Row(PresetFile.OptiFine, "ofFogType",          "3",     "1",     "2"),
        // ofVignette: 0 default, 1 fast, 2 fancy, 3 off.
        Row(PresetFile.OptiFine, "ofVignette",         "3",     "1",     "0"),
        // ofAnimatedWater / Lava: 0 default, 1 fast, 2 off.
        Row(PresetFile.OptiFine, "ofAnimatedWater",    "2",     "1",     "0"),
        Row(PresetFile.OptiFine, "ofAnimatedLava",     "2",     "1",     "0"),
        Row(PresetFile.OptiFine, "ofAnimatedPortal",   "false", "true",  "true"),
        Row(PresetFile.OptiFine, "ofAnimatedRedstone", "false", "true",  "true"),
        Row(PresetFile.OptiFine, "ofAnimatedFlame",    "false", "true",  "true"),
        Row(PresetFile.OptiFine, "ofAnimatedSmoke",    "false", "true",  "true"),
        Row(PresetFile.OptiFine, "ofAnimatedTerrain",  "false", "true",  "true"),
        Row(PresetFile.OptiFine, "ofAnimatedTextures", "false", "true",  "true"),
        // Particle groups: Competitive drops the decorative ones; potion effects stay (they carry information).
        Row(PresetFile.OptiFine, "ofVoidParticles",     "false", "true", "true"),
        Row(PresetFile.OptiFine, "ofWaterParticles",    "false", "true", "true"),
        Row(PresetFile.OptiFine, "ofPortalParticles",   "false", "true", "true"),
        Row(PresetFile.OptiFine, "ofPotionParticles",   "true",  "true", "true"),
        Row(PresetFile.OptiFine, "ofFireworkParticles", "false", "true", "true"),
        Row(PresetFile.OptiFine, "ofDrippingWaterLava", "false", "true", "true"),
        Row(PresetFile.OptiFine, "ofRainSplash",        "false", "true", "true"),
        // ofBetterGrass / ofConnectedTextures: 1 fast, 2 fancy, 3 off.
        Row(PresetFile.OptiFine, "ofBetterGrass",       "3",     "3",    "2"),
        Row(PresetFile.OptiFine, "ofConnectedTextures", "1",     "1",    "2"),
        Row(PresetFile.OptiFine, "ofAfLevel",           "1",     "1",    "4"),
        // ---- Origin's own 1.8.9 toggles (config/originclient-mods.json; ids and defaults from Mods.java) ----
        // Cosmetic sparkles: Mods.GENERAL_SETTINGS "cosmeticEffects".
        Row(PresetFile.Origin, "@general.cosmeticEffects",        "false", "true", "true", "true"),
        // Entity / Tile Entity Distance, percent of the render distance (100 = off): Mods.PERFORMANCE_SETTINGS.
        Row(PresetFile.Origin, "@performance.entityDistance",     "75",    "100",  "100",  "100"),
        Row(PresetFile.Origin, "@performance.tileEntityDistance", "50",    "100",  "100",  "100"),
        // Menu Blur. Inventory blur is off in Competitive, on in Balanced and Quality (orchestrator decision,
        // phase-6 U6.2); the pause menu follows it.
        Row(PresetFile.Origin, "menublur.inventory",              "false", "true", "true", "true"),
        Row(PresetFile.Origin, "menublur.pause",                  "false", "true", "true", "true"),
        // Particle Changer. Its "Reduced" mode also drops crit particles, so no preset uses it (the vanilla
        // "decreased" above keeps crits); Competitive hides block-break particles instead, which carry no
        // information. The changer must be enabled for that, and only Competitive turns it on.
        Row(PresetFile.Origin, "particles.enabled",               "true",  null,   null,   "false"),
        Row(PresetFile.Origin, "particles.hideBlockBreak",        "true",  "false", "false", "false"),
    };

    private static readonly Dictionary<PresetKind, PresetEntry[]> Bundles = Build();

    private static Dictionary<PresetKind, PresetEntry[]> Build()
    {
        var bundles = new Dictionary<PresetKind, PresetEntry[]>();
        foreach (var kind in Enum.GetValues<PresetKind>())
            bundles[kind] = Rows.Select(row => row[(int)kind]).OfType<PresetEntry>().ToArray();
        return bundles;
    }

    public static bool IsSupported(string version) => string.Equals(version, SupportedVersion, StringComparison.Ordinal);

    /// <summary>
    /// The settings of one preset. Only 1.8.9 has a bundle: any other version throws, because a bundle for a
    /// version nobody verified would write keys that version may not read.
    /// </summary>
    public static IReadOnlyList<PresetEntry> For(PresetKind kind, string version)
    {
        if (!IsSupported(version))
            throw new ArgumentOutOfRangeException(nameof(version), version, "Presets exist for 1.8.9 only.");
        return Bundles[kind];
    }

    /// <summary>What the label shows when the files match none of the presets.</summary>
    public const string CustomLabel = "Custom";

    // The bench JSON (tools/bench/presets-189/*.json): {"options": {...}, "optionsof": {...}, "origin": {...}}. Every
    // value is a STRING because prod189.py's apply_preset writes "%s:%s" itself (a JSON true would print as "True").
    // apply_preset ignores the "origin" block (it only seeds options.txt and optionsof.txt). PresetFilesTests keeps
    // the checked-in files equal to this and rewrites them when ORIGIN_REGEN_PRESETS=1.
    public static string ToBenchJson(PresetKind kind)
    {
        var entries = For(kind, SupportedVersion);
        var sb = new System.Text.StringBuilder("{\n");
        var sections = new[] { (PresetFile.Options, "options"), (PresetFile.OptiFine, "optionsof"), (PresetFile.Origin, "origin") };
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
