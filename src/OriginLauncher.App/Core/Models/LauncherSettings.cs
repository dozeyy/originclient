namespace OriginLauncher.App.Core.Models;

public enum PerformanceMode
{
    Graphics,
    Performance
}

public enum LoaderKind
{
    Vanilla,
    Fabric,
    Forge
}

public sealed class LauncherSettings
{
    public int RamMb { get; set; } = 4096;
    public int ResolutionWidth { get; set; } = 1280;
    public int ResolutionHeight { get; set; } = 720;
    public string InstallPath { get; set; } = OriginPaths.Instances;
    public string? SelectedVersion { get; set; }
    public PerformanceMode PerformanceMode { get; set; } = PerformanceMode.Graphics;

    // Null means "follow the automatic per-version recommendation" (Fabric
    // where the perf-mod catalog has data, Forge otherwise) rather than a
    // sticky user override — see HomePage's loader selector.
    public LoaderKind? SelectedLoader { get; set; }
    public bool OptiFineEnabled { get; set; }

    // Developer escape hatch while Microsoft's app-registration approval is
    // pending: lets Play run with a local offline session instead of a real
    // Microsoft sign-in, so the whole launcher/game pipeline can be tested.
    // Surfaced under Settings -> Developer; remove before public release.
    public bool OfflineTestMode { get; set; }
}
