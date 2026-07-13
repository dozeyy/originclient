using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Threading;
using CmlLib.Core;
using CmlLib.Core.Installers;
using CmlLib.Core.ModLoaders.FabricMC;
using CmlLib.Core.ProcessBuilder;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Mods;

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
    // A per-Minecraft-version Origin Client build. One jar per MC API family;
    // several close MC versions can share one jar (1.20 + 1.20.1). Each build's
    // Mixins/fabric.mod.json target its own MC range.
    //
    // BundlesPerfStack distinguishes the two install models:
    //  - true  (1.21.1): the jar jar-in-jars its own pinned Sodium/Iris/Lithium/
    //    ... — so the launcher installs ONLY this jar, purges any standalone perf
    //    jars, and skips PerfModInstaller. Installing both would give Fabric two
    //    copies of the same mod id (e.g. "sodium") and it refuses to start.
    //  - false (1.20/1.20.1): the jar carries only the Origin client itself, so
    //    the launcher installs it ALONGSIDE the standalone perf catalog (Sodium +
    //    Iris + ... for that version) exactly as a vanilla-menu install would get.
    private sealed record OriginBuild(string JarFileName, bool BundlesPerfStack);

    private static readonly IReadOnlyDictionary<string, OriginBuild> OriginBuilds =
        new Dictionary<string, OriginBuild>
        {
            // Full Origin experience, self-contained perf stack.
            ["1.21.1"] = new("originclient-1.21.1.jar", BundlesPerfStack: true),
            // 1.20 API family (src/mods/versions/1.20) — perf stack installed
            // standalone from the catalog alongside it.
            ["1.20"]   = new("originclient-1.20.jar",   BundlesPerfStack: false),
            ["1.20.1"] = new("originclient-1.20.jar",   BundlesPerfStack: false),
            // 1.20.4 API family (src/mods/versions/1.20.4) — same install model
            // as 1.20: Origin jar + standalone perf/shader catalog stack.
            ["1.20.4"] = new("originclient-1.20.4.jar", BundlesPerfStack: false),
            // 1.21 (src/mods/versions/1.21) — LIVE. Same pre-1.21.2-blit-rework
            // API family as 1.21.1 (byte-identical source), but its own mapped
            // build + the standalone install model (1.21's Sodium mc1.21-0.5.11
            // differs from 1.21.1's bundled 0.6.13). 1.21 is a real version + Full
            // in the catalog, so it ships like 1.20.4. runClient at home confirms
            // mixin-apply; a runtime miss fail-softs to vanilla (never crashes).
            ["1.21"] = new("originclient-1.21.jar", BundlesPerfStack: false),
            // 1.21.2 – 1.21.11 (src/mods/staged/1.21.11) — the post-1.21.2
            // blit-rework API family. ONE Origin jar (originclient-1.21.11.jar)
            // covers the whole range: the source is byte-identical across it, so
            // every version in the family installs the same jar (the Mod120 model,
            // where 1.20 + 1.20.1 share one jar). fabric.mod.json declares
            // >=1.21.2- <1.22 to match. All lines STAGED (commented) for two
            // independent reasons:
            //   1. Verification: the jar must be built + javap'd + runClient'd on a
            //      machine with Loom/Mojang access before shipping (CLAUDE.md's
            //      bar); sandbox/CI can't resolve these versions yet. Guide:
            //      src/mods/staged/1.21.11/PORT-12111.md.
            //   2. Shaders: of this family, ONLY 1.21.11 is Full in
            //      PerformanceModCatalog — 1.21.3–1.21.10 are Partial and 1.21.2
            //      is absent, so HasShaderStack hides them regardless of a mapping.
            //      They light up per-version as their catalog entry gains Sodium +
            //      Iris (regenerate PerformanceModCatalog.Data.cs from Modrinth).
            // Uncomment a line once BOTH its shaders exist and the jar is
            // runClient-verified on that version:
            //   ["1.21.2"]  = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            //   ["1.21.3"]  = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            //   ["1.21.4"]  = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            //   ["1.21.5"]  = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            //   ["1.21.6"]  = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            //   ["1.21.7"]  = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            //   ["1.21.8"]  = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            //   ["1.21.9"]  = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            //   ["1.21.10"] = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            //   ["1.21.11"] = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            // 26.2 (src/mods/staged/26.2) — STAGED, not yet active. The module
            // is scaffolded and its Java 25 / unobfuscated-Loom toolchain is
            // proven, but the render layer is mid-port to 26.2's retained-mode GUI
            // and no originclient-26.2.jar builds yet. Listing it here would make
            // the launcher offer an Origin build with no jar to install (broken),
            // so it stays commented until the port compiles + runClient-verifies.
            // The 26.2 perf/shader stack IS live in PerformanceModCatalog, so 26.2
            // is already offered as a Fabric+shaders version (vanilla menus) via
            // HasShaderStack. Re-enable this line to add the Origin menus:
            //   ["26.2"] = new("originclient-26.2.jar", BundlesPerfStack: false),
        };

    // Versions always offered in the picker even if the shader-stack gate below
    // wouldn't include them. 1.21.1's catalog entry deliberately omits Iris (its
    // jar already carries Sodium, so a standalone Iris would double up) — without
    // pinning, HasShaderStack would hide the one version with the full Origin
    // experience. Every version carrying its own bundled perf stack must be pinned
    // for the same reason; versions whose perf stack comes from the catalog are
    // already surfaced by HasShaderStack and don't need pinning.
    private static readonly string[] PinnedVersions =
        OriginBuilds.Where(b => b.Value.BundlesPerfStack).Select(b => b.Key).Distinct().ToArray();

    // Every Minecraft version that ships an Origin Client build (i.e. gets the
    // Origin menus, not just Fabric + perf). Single source of truth for anything
    // that needs to act per-Origin-instance — e.g. the launcher's "Origin UI"
    // toggle, which must reach every such instance, not just one pinned version.
    public static IReadOnlyCollection<string> OriginSupportedVersions => OriginBuilds.Keys.ToArray();

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
    // between versions.
    private static MinecraftPath BuildInstancePath(string version) =>
        new(Path.Combine(OriginPaths.Instances, version));

    // Every launch is Fabric (CLAUDE.md mandate — the Lunar/Feather model):
    // install Fabric + the matching Origin build + the perf/shader stack,
    // then build the launch process. Caller still has to call Process.Start().
    // progress reports human-readable stage text — drives LaunchLoadingOverlay.
    public async Task<Process> InstallAndBuildProcessAsync(
        string version, MLaunchOption option,
        bool externalMods = true,
        IProgress<string>? progress = null, CancellationToken ct = default)
    {
        var path = BuildInstancePath(version);
        var launcher = new MinecraftLauncher(path);
        var modsFolder = Path.Combine(path.BasePath, "mods");
        var configFolder = Path.Combine(path.BasePath, "config");

        // Provisioned unconditionally, not just when a perf profile happens to
        // populate it — the folder must be ready to receive dropped-in .jar
        // files the moment the loader is installed, with zero manual folder
        // creation on the player's part.
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
        Directory.CreateDirectory(modsFolder);
        Directory.CreateDirectory(configFolder);

        progress?.Report("Installing Fabric loader...");
        var fabricInstaller = new FabricInstaller(new HttpClient());
        var versionName = await fabricInstaller.Install(version, path);

        // Every Fabric mod in play here (Origin Client, and any
        // third-party jar a player drops in) depends on this, so it's
        // installed unconditionally for every Fabric version, not
        // just the one Origin Client itself targets.
        await FabricApiInstaller.InstallAsync(version, modsFolder, progress, ct);

        bool originBundlesPerfStack = false;
        if (OriginBuilds.TryGetValue(version, out var originBuild)
            && File.Exists(OriginPaths.BundledOriginClientJar(originBuild.JarFileName)))
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
            File.Copy(OriginPaths.BundledOriginClientJar(originBuild.JarFileName), Path.Combine(modsFolder, "originclient.jar"), overwrite: true);
            originBundlesPerfStack = originBuild.BundlesPerfStack;

            // Only when THIS build carries its own perf stack jar-in-jar
            // (e.g. 1.21.1): purge any STANDALONE copies a pre-bundle
            // install (or a hand-dropped mod) left behind. A stray newer
            // Sodium overrides the bundled 0.6.x and, being incompatible
            // with the pinned Iris 1.8.x, silently disables Iris — killing
            // shaders AND leaving the client in a mixed Sodium state that
            // breaks other Origin mixins. Builds that DON'T bundle the perf
            // stack (1.20) need the standalone jars, so this purge is
            // skipped for them and the catalog install below runs instead.
            //
            // Matched by ModManager.IsBundledPerfJar, which keys on each
            // project's canonical filename SHAPE (sodium-fabric-*, etc.)
            // — so a version-drifted leftover is caught, but user addons
            // like sodium-extra / sodiumdynamiclights are spared (the old
            // bare "sodium" prefix silently deleted those every launch).
            // Only enabled ".jar" files are considered; ".jar.disabled"
            // user mods are left alone.
            if (originBundlesPerfStack)
            {
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
        }

        // Install the standalone perf catalog UNLESS the Origin build we
        // just installed already carries its own pinned Sodium/Indium/
        // Lithium/FerriteCore/Krypton/Iris jar-in-jar (1.21.1) — in that
        // one case installing both would give Fabric two copies of the
        // same mod id. Every other path needs the catalog: a vanilla-menu
        // version with no Origin build, AND a version whose Origin jar
        // ships without the perf stack (1.20). This keeps the perf/shader
        // experience identical whether or not Origin's menus are present.
        bool irisPresent = originBundlesPerfStack; // 1.21.1's bundled Iris (jar-in-jar)
        var perfProfile = originBundlesPerfStack ? null : PerformanceModCatalog.TryGet(version);
        if (perfProfile != null)
        {
            await PerfModInstaller.InstallAsync(perfProfile, modsFolder, progress, ct);
            irisPresent = perfProfile.Iris != null;
        }

        // Iris's own "a new version is available, click to update" nag
        // (net.coderbot.iris.UpdateChecker) has no in-launcher equivalent
        // and would send the player out to a browser mid-session — off by
        // default via Iris's own supported config flag, not a mixin/patch
        // of Iris's code. Idempotent: only sets the one key, never
        // clobbers anything Iris (or the player) already wrote there.
        if (irisPresent)
            IrisConfigSeeder.DisableUpdateMessage(configFolder);

        // Crash-during-write (or power-loss) leaves config files full of
        // NUL bytes — seen in the wild 13 files at once. Depending on the
        // mod that's anywhere from a red "config corrupted" toast every
        // boot (Sodium fail-softs) to a hard crash at entrypoint init
        // (do_a_barrel_roll, ok_zoomer refuse to start). Such a file
        // holds zero real data, so deleting it loses nothing and each
        // mod regenerates its defaults. Swept before every launch.
        SanitizeCorruptConfigs(configFolder);

        // Exactly ONE enabled jar per managed mod family. Fabric hard-
        // fails the entire boot on a duplicate mod id, so a second copy
        // of a launcher-managed mod — a player hand-updating fabric-api
        // next to the launcher's copy, or a catalog version bump leaving
        // the old pinned jar behind — silently turns into "the game
        // never launches". Heal it on every launch. Which twin survives:
        //   1. The catalog PIN for this version, when the family has one
        //      — pins are deliberate era-pairings (Iris 1.7.x must ride
        //      Sodium 0.5.x), so a numerically higher leftover from a
        //      different MC version is exactly the wrong file to keep.
        //   2. Otherwise the highest filename version (a hand-updated
        //      fabric-api is an upgrade — keep it), then most-recently-
        //      landed time when versions don't parse.
        // User (unmanaged) jars group as singletons — never touched.
        var pinnedJarNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (perfProfile != null)
            foreach (var pinned in perfProfile.Mods())
                pinnedJarNames.Add(pinned.FileName);

        foreach (var family in Directory.EnumerateFiles(modsFolder)
                     .Where(f => Path.GetFileName(f).EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase))
                     .Where(f => ModManager.IsManaged(Path.GetFileName(f)))
                     .GroupBy(f => ModManager.ModFamilyKey(Path.GetFileName(f)), StringComparer.OrdinalIgnoreCase))
        {
            var keep = family.FirstOrDefault(f => pinnedJarNames.Contains(Path.GetFileName(f)))
                ?? family
                    .OrderByDescending(f => ModManager.TryParseVersion(Path.GetFileName(f)) ?? new Version(0, 0))
                    .ThenByDescending(f => Max(File.GetCreationTimeUtc(f), File.GetLastWriteTimeUtc(f)))
                    .First();
            foreach (var stale in family.Where(f => !ReferenceEquals(f, keep)))
            {
                try { File.Delete(stale); } catch { /* locked/removed already */ }
            }
        }

        // "Play with external mods" OFF: the game must load ONLY the
        // jars Origin itself provisions. Fabric Loader's supported
        // fabric.modsFolder property points it at a launcher-owned
        // folder rebuilt fresh each launch from the managed set just
        // provisioned above — the player's mods/ folder (and every
        // enabled/disabled state in it) is never touched, so flipping
        // the switch back on restores their setup exactly. Rebuilding
        // from scratch each launch means the folder can never drift
        // stale relative to what a normal launch would install.
        if (!externalMods)
        {
            progress?.Report("Preparing Origin-only mod set...");
            var originOnlyFolder = Path.Combine(path.BasePath, "mods-origin-only");
            Directory.CreateDirectory(originOnlyFolder);

            // Managed = Origin-provisioned: originclient.jar, Fabric API,
            // and the perf/shader stack. Everything else is the player's
            // and stays out of this launch.
            var desired = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var file in Directory.EnumerateFiles(modsFolder))
            {
                var name = Path.GetFileName(file);
                if (name.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase)
                    && ModManager.IsManaged(name))
                {
                    desired[name] = file;
                }
            }

            // Diff-sync rather than delete+recreate: a still-running game
            // instance holds these jars open (Windows file locks), which
            // made a relaunch-while-playing fail with IOException. An
            // unchanged set now needs zero file ops; stale extras are
            // removed best-effort (a locked one belongs to the running
            // instance and gets cleaned on the next launch instead).
            foreach (var existing in Directory.EnumerateFiles(originOnlyFolder))
            {
                if (!desired.ContainsKey(Path.GetFileName(existing)))
                {
                    try { File.Delete(existing); } catch { /* locked by a running instance */ }
                }
            }
            foreach (var (name, source) in desired)
            {
                var dest = Path.Combine(originOnlyFolder, name);
                try
                {
                    if (!File.Exists(dest) || new FileInfo(dest).Length != new FileInfo(source).Length)
                        File.Copy(source, dest, overwrite: true);
                }
                catch (IOException) when (File.Exists(dest))
                {
                    // Locked by a still-running instance but present — the
                    // existing copy is loadable, and launching with it
                    // beats failing the whole launch.
                }
            }

            var extraJvm = (option.ExtraJvmArguments ?? Enumerable.Empty<MArgument>()).ToList();
            extraJvm.Add(new MArgument($"-Dfabric.modsFolder={originOnlyFolder}"));
            option.ExtraJvmArguments = extraJvm;
        }

        progress?.Report("Downloading game files...");
        var fileProgress = new Progress<InstallerProgressChangedEventArgs>(e =>
            progress?.Report($"Downloading game files ({e.ProgressedTasks}/{e.TotalTasks})"));

        return await launcher.InstallAndBuildProcessAsync(versionName, option, fileProgress, null, ct);
    }

    private static DateTime Max(DateTime a, DateTime b) => a > b ? a : b;

    // Recursively deletes config files that contain ONLY NUL/whitespace bytes —
    // the signature of a crash- or power-loss-interrupted write (NTFS allocates
    // the length but the data never hits disk). Deliberately extension-agnostic
    // (json, json5, toml, even .txt configs corrupt this way) and deliberately
    // conservative: a single real byte anywhere means the file is left alone,
    // so genuine settings can never be lost. Never throws — a bad launch beats
    // a blocked one, and every mod regenerates defaults for a missing file.
    private static void SanitizeCorruptConfigs(string configFolder)
    {
        try
        {
            if (!Directory.Exists(configFolder)) return;
            foreach (var file in Directory.EnumerateFiles(configFolder, "*", SearchOption.AllDirectories))
            {
                try
                {
                    var info = new FileInfo(file);
                    if (info.Length == 0 || info.Length > 4 * 1024 * 1024) continue;

                    var blank = true;
                    foreach (var b in File.ReadAllBytes(file))
                    {
                        if (b != 0x00 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x20)
                        {
                            blank = false;
                            break;
                        }
                    }
                    if (blank)
                        File.Delete(file);
                }
                catch
                {
                    // Unreadable/locked — leave it; the owning mod deals with it.
                }
            }
        }
        catch
        {
            // Best-effort sweep only.
        }
    }
}
