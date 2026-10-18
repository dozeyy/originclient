namespace OriginLauncher.App.Core.Models;

// There is no loader choice (CLAUDE.md mandate): every version is Fabric
// except 1.8.9, which the launcher quietly runs on Forge + OptiFine — nothing
// here selects it. No Graphics/Performance mode either. Old settings.json
// files that still carry those removed keys deserialize fine — System.Text.Json
// ignores unknown properties.
public sealed class LauncherSettings
{
    public int RamMb { get; set; } = 4096;
    public int ResolutionWidth { get; set; } = 1280;
    public int ResolutionHeight { get; set; } = 720;
    public string InstallPath { get; set; } = OriginPaths.Instances;
    public string? SelectedVersion { get; set; }

    // Shader disk-cache tuning applied to the game process at launch: keeps the
    // GPU driver's compiled-shader cache large and un-pruned so Iris shaderpacks
    // don't recompile (and stutter) every session. Quality-neutral — pure
    // smoothness, no visual change. Split per vendor because the driver knobs
    // differ: NVIDIA has real OpenGL cache env vars on Windows; AMD's are the
    // Mesa/RadeonSI variables (Linux/Proton — on Windows AMD's GL cache is
    // driver-managed, so that toggle is a safe no-op there). Both default on; a
    // missing key in an older settings.json deserializes to these initializers.
    // Surfaced under Settings -> Performance.
    public bool ShaderCacheNvidia { get; set; } = true;
    public bool ShaderCacheAmd { get; set; } = true;

    // Developer escape hatch while Microsoft's app-registration approval is
    // pending: lets Play run with a local offline session instead of a real
    // Microsoft sign-in, so the whole launcher/game pipeline can be tested.
    // Surfaced under Settings -> Developer; remove before public release.
    public bool OfflineTestMode { get; set; }

    // "Play with external mods" switch on Home. ON (default — matches the
    // behavior every existing install already has): the instance's mods/
    // folder loads as-is, the player's own jars included. OFF: the game is
    // pointed at a launcher-owned folder containing only the jars Origin
    // itself provisions (via Fabric's fabric.modsFolder property; on Forge
    // 1.8.9 via the account's mods mirror — see VersionManager), so a broken
    // third-party mod can never break a launch.
    // The player's mods/ folder is never modified by this — flip it back on
    // and everything loads exactly as before.
    public bool PlayWithExternalMods { get; set; } = true;

    // Mod profiles (Profiles page, Core/Profiles): the player's named sets of
    // their OWN mods, one Minecraft version each, optionally with a server to
    // join from Home. Empty by default, and SelectedProfileId null = "no
    // profile": the version's normal mods folder, exactly as before profiles
    // existed. A selected profile only applies to its own version
    // (ProfileRules.Active). Written only via SettingsStore.Update + ProfileBook.
    public List<Profiles.ModProfile> ModProfiles { get; set; } = new();
    public string? SelectedProfileId { get; set; }

    // "Sync settings across versions" (Settings -> Origin Client). ON by
    // default (Will, 2026-10-18 — it used to be off and nobody knew it existed):
    // keybinds, video settings, every Origin mod setting, HUD positions and the
    // server list follow the player to every version — see Core/SettingsSync.cs.
    // Renamed from SettingsSyncEnabled so everyone's old saved "off" is dropped
    // once and they start on. Turning it off just stops syncing; every instance
    // keeps whatever it has.
    public bool SyncSettingsAcrossVersions { get; set; } = true;

    // Custom JVM arguments, keyed by Minecraft version id (Settings -> JVM
    // arguments). Versions the player never customized are simply absent, so an
    // older settings.json upgrades to an empty map and behaves exactly as before.
    public Dictionary<string, VersionJvmOptions> JvmArgsByVersion { get; set; } = new();

    /// <summary>The saved JVM options for a version, or null when it has none.</summary>
    public VersionJvmOptions? JvmArgsFor(string? version) =>
        version != null && JvmArgsByVersion.TryGetValue(version, out var opts) && !opts.IsEmpty
            ? opts
            : null;
}
