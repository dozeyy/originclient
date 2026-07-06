namespace OriginLauncher.App.Core.Models;

public enum PerformanceMode
{
    Graphics,
    Performance
}

public sealed class LauncherSettings
{
    public int RamMb { get; set; } = 4096;
    public int ResolutionWidth { get; set; } = 1280;
    public int ResolutionHeight { get; set; } = 720;
    public string InstallPath { get; set; } = OriginPaths.Instances;
    public string? SelectedVersion { get; set; }
    public PerformanceMode PerformanceMode { get; set; } = PerformanceMode.Graphics;
}
