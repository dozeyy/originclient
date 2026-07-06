using System.Diagnostics;
using CmlLib.Core;
using CmlLib.Core.ProcessBuilder;

namespace OriginLauncher.App.Core.Versions;

public sealed class VersionManager
{
    private readonly MinecraftLauncher _launcher;

    public VersionManager()
    {
        _launcher = new MinecraftLauncher(new MinecraftPath(OriginPaths.Instances));
    }

    public async Task<IReadOnlyList<string>> GetReleaseVersionsAsync()
    {
        var versions = await _launcher.GetAllVersionsAsync();
        return versions
            .Where(v => v.Type == "release")
            .Select(v => v.Name)
            .ToList();
    }

    // Downloads/verifies the version's files (no-op if already installed)
    // and builds the launch process — caller still has to call Process.Start().
    public async Task<Process> InstallAndBuildProcessAsync(string version, MLaunchOption option, CancellationToken ct = default)
    {
        return await _launcher.InstallAndBuildProcessAsync(version, option, ct);
    }
}
