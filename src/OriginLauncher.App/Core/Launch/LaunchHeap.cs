using System.Threading;
using System.Threading.Tasks;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Launch;

/// <summary>Everything a launch needs to know about its memory, gathered off the UI thread.</summary>
public static class LaunchHeap
{
    /// <summary>
    /// The heap plan for this launch and whether the version is 1.8.9-era (legacy Forge).
    /// The pack scan is file IO, and only an AUTO legacy launch needs it, so it is skipped otherwise.
    /// </summary>
    public static async Task<(HeapPlan Heap, bool Legacy)> PlanAsync(
        LauncherSettings settings, string version, string gameDir, CancellationToken ct)
    {
        var legacy = LegacyForgeInstaller.IsLegacy(version);
        var heavy = legacy && !RamPolicy.IsManual(settings)
            && await Task.Run(() => PackProbe.IsHeavy(OriginPaths.ModsDir(version), gameDir), ct);
        return (HeapPlan.For(settings, SystemInfo.GetTotalPhysicalMemoryMb(), legacy, heavy), legacy);
    }
}
