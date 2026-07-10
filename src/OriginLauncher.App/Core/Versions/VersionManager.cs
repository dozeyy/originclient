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
using OriginLauncher.App.Core.Mods;
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

    // The supported version set. Modern versions are gated on the perf catalog
    // having a real shader stack (Sodium + Iris) for them — Origin never offers
    // a version where shaders/Sodium don't exist yet, which drops brand-new
    // Minecraft releases the perf mods haven't caught up to (they reappear the
    // moment the catalog is regenerated with their Sodium/Iris builds).
    //
    // The two classic pillars 1.8.9 and 1.12.2 are kept explicitly: they launch
    // via Legacy Fabric and predate Sodium/Iris entirely, so the catalog gate
    // can't include them. (1.16.5 is the oldest catalog-shader version, so it's
    // covered by the gate and doesn't need pinning.)
    private static readonly string[] PinnedVersions = { "1.8.9", "1.12.2" };

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

        // Only offer versions that actually work end-to-end: the classic
        // pillars, plus any modern release the perf catalog has a full
        // Sodium + Iris stack for. This deliberately hides brand-new Minecraft
        // versions until the perf mods (and therefore shaders) support them.
        releases = releases.Where(v =>
            PinnedVersions.Contains(v.Name)
            || PerformanceModCatalog.HasShaderStack(v.Name)).ToList();

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
        var configFolder = Path.Combine(path.BasePath, "config");

        // Provisioned unconditionally for any loader, not just when a perf
        // profile or OptiFine happens to populate it — the folder must be
        // ready to receive dropped-in .jar files the moment a loader is
        // installed, with zero manual folder creation on the player's part.
        //
        // config/ is created for the same reason, and it fixes a real
        // first-launch crash: FerriteCore (part of every Fabric perf stack)
        // calls Files.createFile("config/ferritecore.mixin.properties") during
        // early mixin init, and createFile does NOT create parent dirs — so on
        // a brand-new instance with no config/ folder it throws
        // NoSuchFileException, FerriteConfig's static init fails, and Minecraft
        // hard-crashes. The *second* launch worked only because Sodium/Lithium
        // had since created config/ while writing their own defaults. Making
        // the folder exist up front removes the "first run always crashes"
        // class entirely, for any config-writing mod, on any version.
        if (loader != LoaderKind.Vanilla)
        {
            Directory.CreateDirectory(modsFolder);
            Directory.CreateDirectory(configFolder);
        }

        var versionName = version;

        switch (loader)
        {
            // Pre-1.14 "Fabric" = Legacy Fabric (the community port of the
            // same loader; official Fabric starts at 1.14). Same player-facing
            // toggle — the launcher owning the loader flavor is the product
            // model (VERSIONS.md). No Origin Client jar and no perf catalog
            // here: neither targets these versions yet (menus stay vanilla on
            // legacy versions until the Tier C port).
            case LoaderKind.Fabric when LegacyFabricInstaller.Supports(version):
            {
                versionName = await LegacyFabricInstaller.InstallAsync(version, path, progress, ct);
                await FabricApiInstaller.InstallLegacyAsync(version, modsFolder, progress, ct);
                break;
            }
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
                    // Remove ANY previously-installed Origin Client jar first,
                    // whatever its filename — a stale jar left by an older
                    // launcher build (e.g. a pre-mod-system release) would
                    // otherwise keep loading in-game even after the launcher
                    // updated. This is exactly the "old client through the
                    // launcher" symptom: the launcher was new, the instance
                    // jar was old. Always ship the launcher's own bundled jar.
                    //
                    // Enumerate ALL files and filter by EndsWith rather than a
                    // Win32 "originclient*.jar" glob — the legacy 3-char-ext
                    // match is ambiguous. Enabled ".jar" only; a user's own
                    // ".jar.disabled" is never touched.
                    foreach (var file in Directory.EnumerateFiles(modsFolder))
                    {
                        var fn = Path.GetFileName(file);
                        if (fn.StartsWith("originclient", StringComparison.OrdinalIgnoreCase)
                            && fn.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase))
                        {
                            try { File.Delete(file); } catch { /* locked/removed already */ }
                        }
                    }
                    File.Copy(OriginPaths.BundledOriginClientJar, Path.Combine(modsFolder, "originclient.jar"), overwrite: true);
                    originClientInstalled = true;

                    // Origin Client bundles its own pinned Sodium/Indium/Lithium/
                    // FerriteCore/Krypton/Iris as jar-in-jar. Purge any STANDALONE
                    // copies a pre-bundle install (or a hand-dropped mod) left
                    // behind: a stray newer Sodium overrides the bundled 0.6.x
                    // and, being incompatible with the pinned Iris 1.8.x, silently
                    // disables Iris — killing shaders AND leaving the client in a
                    // mixed Sodium state that breaks other Origin mixins.
                    //
                    // Matched by ModManager.IsBundledPerfJar, which keys on each
                    // project's canonical filename SHAPE (sodium-fabric-*, etc.)
                    // — so a version-drifted leftover is caught, but user addons
                    // like sodium-extra / sodiumdynamiclights are spared (the old
                    // bare "sodium" prefix silently deleted those every launch).
                    // Only enabled ".jar" files are considered; ".jar.disabled"
                    // user mods are left alone.
                    foreach (var file in Directory.EnumerateFiles(modsFolder))
                    {
                        var name = Path.GetFileName(file);
                        if (!name.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase))
                            continue;
                        if (name.Equals("originclient.jar", StringComparison.OrdinalIgnoreCase))
                            continue;
                        if (ModManager.IsBundledPerfJar(name))
                        {
                            try { File.Delete(file); } catch { /* locked/removed already */ }
                        }
                    }
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
