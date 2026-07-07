using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Threading;
using CmlLib.Core;
using CmlLib.Core.Installer.Forge;
using CmlLib.Core.Installers;
using CmlLib.Core.ModLoaders.FabricMC;
using CmlLib.Core.ProcessBuilder;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Versions;

public sealed class VersionManager
{
    // Only used for GetReleaseVersionsAsync, which just queries Mojang's
    // global manifest — the path is irrelevant there, so one shared launcher
    // is fine. InstallAndBuildProcessAsync below builds its own
    // per-version-rooted launcher instead (see BuildInstancePath), because
    // mods/saves/config must stay isolated per Minecraft version.
    private static readonly MinecraftLauncher ManifestLauncher = new(new MinecraftPath(OriginPaths.Instances));

    // HomePage and SettingsPage each construct their own VersionManager and
    // both fetch the release list at startup — without this, they raced to
    // write the same cached manifest file at the same path simultaneously
    // ("file in use by another process"), which is what was actually causing
    // the intermittent "No versions found" failures, not network flakiness.
    // Static + gated so every VersionManager instance in the process shares
    // one in-flight fetch; only a successful result is cached, so a transient
    // failure doesn't permanently break the session.
    private static Task<IReadOnlyList<string>>? _cachedReleaseVersions;
    private static readonly SemaphoreSlim ReleaseVersionsGate = new(1, 1);

    // Oldest supported release — Origin doesn't target anything older than this,
    // so the picker only ever shows 1.7.10 and newer instead of the full
    // Mojang history back to alpha.
    private const string OldestSupportedVersion = "1.7.10";

    // Must match minecraft_version in src/OriginClient.Mod/gradle.properties.
    // The bundled jar's own Mixins/fabric.mod.json target this exact MC
    // version, and it jar-in-jars its own copy of the same perf-mod stack
    // PerformanceModCatalog would otherwise install standalone — installing
    // both into the same instance gives Fabric Loader two copies of the same
    // mod id (e.g. "sodium") and it refuses to start. So this version is the
    // one case where the bundled jar replaces PerfModInstaller instead of
    // sitting alongside it.
    private const string OriginClientModVersion = "1.21.1";

    public async Task<IReadOnlyList<string>> GetReleaseVersionsAsync()
    {
        await ReleaseVersionsGate.WaitAsync();
        try
        {
            _cachedReleaseVersions ??= FetchReleaseVersionsAsync();
            try
            {
                return await _cachedReleaseVersions;
            }
            catch
            {
                _cachedReleaseVersions = null;
                throw;
            }
        }
        finally
        {
            ReleaseVersionsGate.Release();
        }
    }

    private static async Task<IReadOnlyList<string>> FetchReleaseVersionsAsync()
    {
        var versions = await ManifestLauncher.GetAllVersionsAsync();
        var releases = versions.Where(v => v.Type == "release").ToList();

        var cutoff = releases.FirstOrDefault(v => v.Name == OldestSupportedVersion)?.ReleaseTime;
        if (cutoff != null)
            releases = releases.Where(v => v.ReleaseTime >= cutoff).ToList();

        return releases.Select(v => v.Name).ToList();
    }

    // Every version gets its own root under /instances/{version}/ — hard
    // requirement (see CLAUDE.md): mods, saves, and configs must not leak
    // between versions, which matters a lot more now that Fabric/Forge
    // versions carry their own perf-mod/OptiFine jars.
    private static MinecraftPath BuildInstancePath(string version) =>
        new(Path.Combine(OriginPaths.Instances, version));

    // Installs the right loader for the version (Fabric perf-mod stack,
    // Forge + optional OptiFine, or plain vanilla), then installs/builds the
    // launch process. Caller still has to call Process.Start(). progress
    // reports human-readable stage text — used to drive LaunchLoadingOverlay.
    public async Task<Process> InstallAndBuildProcessAsync(
        string version, LoaderKind loader, bool optiFineEnabled, MLaunchOption option,
        IProgress<string>? progress = null, CancellationToken ct = default)
    {
        var path = BuildInstancePath(version);
        var launcher = new MinecraftLauncher(path);
        var modsFolder = Path.Combine(path.BasePath, "mods");

        // Provisioned unconditionally for any loader, not just when a perf
        // profile or OptiFine happens to populate it — the folder must be
        // ready to receive dropped-in .jar files the moment a loader is
        // installed, with zero manual folder creation on the player's part.
        if (loader != LoaderKind.Vanilla)
            Directory.CreateDirectory(modsFolder);

        var versionName = version;

        switch (loader)
        {
            case LoaderKind.Fabric:
            {
                progress?.Report("Installing Fabric loader...");
                var fabricInstaller = new FabricInstaller(new HttpClient());
                versionName = await fabricInstaller.Install(version, path);

                // Every Fabric mod in play here (Origin Client, and any
                // third-party jar a player drops in) depends on this, so it's
                // installed unconditionally for every Fabric version, not
                // just the one Origin Client itself targets.
                await FabricApiInstaller.InstallAsync(version, modsFolder, progress, ct);

                bool originClientInstalled = false;
                if (version == OriginClientModVersion && File.Exists(OriginPaths.BundledOriginClientJar))
                {
                    progress?.Report("Installing Origin Client...");
                    File.Copy(OriginPaths.BundledOriginClientJar, Path.Combine(modsFolder, "originclient.jar"), overwrite: true);
                    originClientInstalled = true;
                }

                // Origin Client already carries its own pinned Sodium/Indium/
                // Lithium/FerriteCore/Krypton as jar-in-jar — only fall back
                // to the standalone catalog when it wasn't installed (older/
                // newer versions Origin Client doesn't target yet, or a dev
                // checkout that hasn't built the mod), so a real instance
                // never ends up with both.
                if (!originClientInstalled)
                {
                    var profile = PerformanceModCatalog.TryGet(version);
                    if (profile != null)
                        await PerfModInstaller.InstallAsync(profile, modsFolder, progress, ct);
                }
                break;
            }
            case LoaderKind.Forge:
            {
                progress?.Report("Installing Forge loader...");
                var forgeInstaller = new ForgeInstaller(launcher);
                versionName = await forgeInstaller.Install(version);

                if (optiFineEnabled && OptiFineCacheStore.IsCached(version))
                {
                    progress?.Report("Adding OptiFine...");
                    Directory.CreateDirectory(modsFolder);
                    File.Copy(
                        OptiFineCacheStore.JarPathFor(version),
                        Path.Combine(modsFolder, "OptiFine.jar"),
                        overwrite: true);
                }
                break;
            }
            case LoaderKind.Vanilla:
            default:
                break;
        }

        progress?.Report("Downloading game files...");
        var fileProgress = new Progress<InstallerProgressChangedEventArgs>(e =>
            progress?.Report($"Downloading game files ({e.ProgressedTasks}/{e.TotalTasks})"));

        return await launcher.InstallAndBuildProcessAsync(versionName, option, fileProgress, null, ct);
    }
}
