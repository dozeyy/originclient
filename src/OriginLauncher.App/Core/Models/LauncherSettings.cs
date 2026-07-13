namespace OriginLauncher.App.Core.Models;

// Origin is Fabric-only (CLAUDE.md mandate): there is no loader choice, no
// Forge/OptiFine path, and no Graphics/Performance mode. Old settings.json
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
    // itself provisions (via Fabric's fabric.modsFolder property — see
    // VersionManager), so a broken third-party mod can never break a launch.
    // The player's mods/ folder is never modified by this — flip it back on
    // and everything loads exactly as before.
    public bool PlayWithExternalMods { get; set; } = true;
}
