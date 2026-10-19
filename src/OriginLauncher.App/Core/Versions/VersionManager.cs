using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Threading;
using CmlLib.Core;
using CmlLib.Core.Installers;
using CmlLib.Core.ModLoaders.FabricMC;
using CmlLib.Core.ProcessBuilder;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.Core.Versions;

public sealed class VersionManager
{
    // Only used for GetReleaseVersionsAsync, which just queries Mojang's
    // global manifest — the path is irrelevant there, so one shared launcher
    // is fine. InstallAndBuildProcessAsync below builds its own
    // per-version-rooted launcher instead (see GameFolderPath), because
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
            // 1.21.5 — PULLED 2026-08-06 (Will's call: dropped from the supported
            // lineup). It is also removed from VersionCatalog, so it no longer
            // appears in the picker at all — same treatment as 1.21.9. The module
            // (src/mods/versions/1.21.5) stays on disk but is no longer built by
            // CI or bundled into the launcher.
            // 1.21.8 (src/mods/versions/1.21.8) — the Matrix3x2fStack + no-
            // setShaderColor era, still on the pre-1.21.9 input API + String key
            // categories + old WorldRenderEvents path. Own build, boot-verified.
            ["1.21.8"] = new("originclient-1.21.8.jar", BundlesPerfStack: false),
            // 1.21.10 – 1.21.11 (src/mods/versions/1.21.11) — the render-
            // pipeline + world-event-v2 API era. ONE Origin jar
            // (originclient-1.21.11.jar) covers both; fabric.mod.json declares
            // >=1.21.10- <1.22. BOTH boot-verified through the launcher
            // pipeline with zero mixin-apply failures + full Origin UI (2026-07-13).
            //
            // IMPORTANT — this jar canNOT go lower than 1.21.10. Verified
            // runtime boundaries below it (each a genuinely-new-at-that-version
            // class the compiled jar references, so it NoClassDefFounds on older
            // versions — proven by the per-version boot sweep):
            //   • 1.21.10: Fabric API moved WorldRenderEvents into the .world
            //     subpackage (1.21.9 crashes: NoClassDefFoundError …world/WorldRenderEvents).
            //   • 1.21.9:  new input-event API (MouseButtonEvent) + typed
            //     KeyMapping.Category (class_304$class_11900).
            //   • 1.21.6:  GuiGraphics transforms moved to Matrix3x2fStack;
            //     RenderSystem.setShaderColor removed (color rides the pipeline).
            //   • 1.21.5:  hitboxes extracted into HitboxRenderState.
            // Each 1.21.x sub-family needs its OWN build (a real port, not a
            // config flip). Shipped so far: 1.21.5 + 1.21.8 (above) and
            // 1.21.10 + 1.21.11 (here) — the popular ones Will picked, each
            // boot-verified. Still absent (so the picker greys them "Coming
            // Soon" — a vanilla-menu ship would violate mandate #2): 1.21.2,
            // 1.21.3, 1.21.4, 1.21.6, 1.21.7, 1.21.9. Each needs its own port +
            // boot verify; the 1.21.5/1.21.8 modules are the templates for the
            // sub-families around them.
            // 1.21.10 and 1.21.11 are SEPARATE sub-families: Mojang's 1.21.11 mapping
            // rename wave (ResourceLocation→Identifier, RenderType→rendertype.RenderType,
            // projectile subpackages, DynamicTexture.setFilter removed→getSamplerCache)
            // means the 1.21.11 jar NoClassDefFounds on 1.21.10. Each has its own build.
            ["1.21.10"] = new("originclient-1.21.10.jar", BundlesPerfStack: false),
            ["1.21.11"] = new("originclient-1.21.11.jar", BundlesPerfStack: false),
            // --- Gap sub-families staged 2026-07-13 (src/mods/staged/*) ---
            // Compile-verified against each version's mapped jar; ENABLED here for
            // local boot-testing. Each still needs a `runClient`/launcher boot check
            // (zero Mixin-apply failures + full Origin menus) before any release tag.
            // Perf/shader stack for all of these is already Full in the catalog.
            //
            // 1.21.2/1.21.3/1.21.4 — one API family (pre-HitboxRenderState, post-
            // 1.21.2 blit). ONE jar (originclient-1.21.4.jar), range >=1.21.2- <1.21.5.
            ["1.21.2"] = new("originclient-1.21.4.jar", BundlesPerfStack: false),
            ["1.21.3"] = new("originclient-1.21.4.jar", BundlesPerfStack: false),
            ["1.21.4"] = new("originclient-1.21.4.jar", BundlesPerfStack: false),
            // 1.21.6/1.21.7 — same API family as the live 1.21.8 (its jar already
            // declares >=1.21.6- <1.21.9). ONE jar (originclient-1.21.6.jar), built
            // from the 1.21.8 source with zero code deltas.
            ["1.21.6"] = new("originclient-1.21.6.jar", BundlesPerfStack: false),
            ["1.21.7"] = new("originclient-1.21.6.jar", BundlesPerfStack: false),
            // 1.20.2 — same 1.20.2+ renderBackground family as the live 1.20.4 (zero
            // code deltas; background mixin descriptors javap-verified identical).
            ["1.20.2"] = new("originclient-1.20.2.jar", BundlesPerfStack: false),
            // --- Pre-1.20 PoseStack era, staged 2026-07-14 (src/mods/staged/*) ---
            // The pre-GuiGraphics rendering backend (GuiComponent + PoseStack draws,
            // Gfx wrapper). ENABLED for local boot-testing; each needs the boot sweep
            // (zero mixin-apply failures + full Origin menus + shaders) before a
            // release tag. Coverage mirrors Lunar's picker: 1.16.5 / 1.17.1 / 1.18.2 /
            // 1.19.2 / 1.19.3 / 1.19.4. Not offered although the jars would run
            // there: 1.17, 1.18, 1.18.1, 1.19, 1.19.1 (1.18.1's only Sodium is an
            // alpha + Iris a pre-release — "never broken" outranks coverage; the
            // rest can be added later by pointing at the family jar + boot sweep).
            //
            // 1.19.4 — JOML/Axis + Button.builder + renderWidget era.
            ["1.19.4"] = new("originclient-1.19.4.jar", BundlesPerfStack: false),
            // 1.19.3 — its OWN build: it shares 1.19.4's JOML/builder era but
            // still uses the renderButton-era widgets, has no LogoRenderer and
            // keeps the dirt background — the 1.19.4 jar silently degrades to
            // vanilla styling there (found by the 1.19.4 port's floor audit),
            // which mandate #2 forbids shipping.
            ["1.19.3"] = new("originclient-1.19.3.jar", BundlesPerfStack: false),
            // 1.19–1.19.2 — one jar (pre-JOML: Vector3f/Quaternion, Button ctor),
            // range >=1.19- <1.19.3. Only 1.19.2 offered for now.
            ["1.19.2"] = new("originclient-1.19.2.jar", BundlesPerfStack: false),
            // 1.18.x — one jar, range >=1.18- <1.19-; TextComponent era. Only
            // 1.18.2 offered (see the alpha-stack note above).
            ["1.18.2"] = new("originclient-1.18.2.jar", BundlesPerfStack: false),
            // 1.17.x — one jar, Java 16 bytecode, range >=1.17- <1.18-.
            ["1.17.1"] = new("originclient-1.17.1.jar", BundlesPerfStack: false),
            // 1.16.5 — Java 8 bytecode, fixed-function GL era (no RenderSystem
            // shader API): the oldest Fabric version Origin ships.
            ["1.16.5"] = new("originclient-1.16.5.jar", BundlesPerfStack: false),
            // --- LEGACY (Forge + OptiFine) ---
            // 1.8.9 (src/mods/versions/1.8.9) — LIVE again (Will, 2026-10-13):
            // the PvP-era version, on Forge 11.15.1.2318 + OptiFine HD_U_M5 —
            // Fabric never supported it and OptiFine is its only shader layer.
            // The player still never picks a loader: InstallAndBuildCoreAsync
            // takes the LEGACY BRANCH for it quietly. BundlesPerfStack is
            // meaningless there (the legacy branch never installs the Fabric
            // catalog; LegacyStackInstaller is its perf stack).
            ["1.8.9"] = new("originclient-1.8.9.jar", BundlesPerfStack: false),
            // 1.12.2 stays PULLED (2026-08-06, Will's call): out of
            // VersionCatalog and CI. Its module and its LegacyForgeInstaller /
            // OptiFineInstaller / LegacyStackInstaller entries are kept, so it
            // could return the same way 1.8.9 did.
            // --- 26.x (unobfuscated, Java 25, extract/submit GUI) ---
            // 26.3 — derived from the 26.2 module 2026-10-13 (src/mods/versions/26.3):
            // SDL3 window/input and the com.mojang.renderpearl render API. 26.3 has no
            // patch releases yet; the jar declares ~26.3. Perf/shader stack standalone.
            ["26.3"] = new("originclient-26.3.jar", BundlesPerfStack: false),
            // 26.2 — ported from the 1.21.11 module 2026-10-12 (src/mods/versions/26.2);
            // perf/shader stack installed standalone from PerformanceModCatalog.
            // Mojang's version JSON asks for a Java 25 runtime, which CmlLib
            // downloads like every other version's.
            ["26.2"] = new("originclient-26.2.jar", BundlesPerfStack: false),
            // 26.1 – 26.1.2 — ONE jar (src/mods/versions/26.1, derived from 26.2
            // 2026-10-13): the three releases share one API (version-scout API diff:
            // no class or public signature changed). Perf/shader stack standalone;
            // the Sodium/Iris pair differs per patch (PerformanceModCatalog).
            ["26.1.2"] = new("originclient-26.1.jar", BundlesPerfStack: false),
            ["26.1.1"] = new("originclient-26.1.jar", BundlesPerfStack: false),
            ["26.1"]   = new("originclient-26.1.jar", BundlesPerfStack: false),
        };

    // Every Minecraft version that ships an Origin Client build (i.e. gets the
    // Origin menus, not just Fabric + perf). Single source of truth for anything
    // that needs to act per-Origin-instance — e.g. the launcher's "Origin UI"
    // toggle, which must reach every such instance, not just one pinned version.
    public static IReadOnlyCollection<string> OriginSupportedVersions => OriginBuilds.Keys.ToArray();

    /// <summary>
    /// The bundled originclient-&lt;jar&gt;.jar a version ships (OriginPaths.BundledOriginClientJar), or null for
    /// a version with no Origin build. The path is not checked for existence: a dev build without the
    /// module's Gradle output has none. Read-only; the import uses it to learn which mods Origin already includes.
    /// </summary>
    public static string? BundledJarFor(string version) =>
        OriginBuilds.TryGetValue(version, out var build) ? OriginPaths.BundledOriginClientJar(build.JarFileName) : null;

    /// <summary>
    /// Takes the version's provisioning gate (the lock an install or launch of that version holds) for as
    /// long as the result is kept; dispose it to release. The import holds it while it writes the version's
    /// shared game files, so a launch of the same version waits instead of racing it.
    /// </summary>
    public static async Task<IAsyncDisposable> AcquireVersionGateAsync(string version, CancellationToken ct)
    {
        var gate = GateFor(version);
        await gate.WaitAsync(ct).ConfigureAwait(false);
        return new GateLease(gate);
    }

    private sealed class GateLease : IAsyncDisposable
    {
        private SemaphoreSlim? _gate;
        public GateLease(SemaphoreSlim gate) => _gate = gate;

        public ValueTask DisposeAsync()
        {
            try
            {
                Interlocked.Exchange(ref _gate, null)?.Release();
            }
            catch (Exception ex) when (ex is SemaphoreFullException or ObjectDisposedException)
            {
                // Releasing a gate must never throw into the import that holds it (the import has already finished or rolled back).
                Import.Launcher.ImportLog.Write("version gate release failed: " + ex.GetType().Name);
            }
            return ValueTask.CompletedTask;
        }
    }

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

        // Only offer versions that ship a verified Origin build. Mandate #2:
        // a version with shaders but no Origin menus (vanilla-looking) is NOT
        // an acceptable shipped state, so "offered" must mean "has an Origin
        // build in OriginBuilds" — not merely "has a shader stack". This keeps
        // the SettingsPage version list in lockstep with the grid picker (whose
        // supported set is OriginBuilds.Keys) and guarantees the launcher never
        // provisions a jarless, vanilla-menu instance.
        releases = releases.Where(v => OriginBuilds.ContainsKey(v.Name)).ToList();

        return releases.Select(v => v.Name).ToList();
    }

    // Every version gets its own root under /instances/{version}/ and every
    // account its own game folder inside it — hard requirement (see CLAUDE.md):
    // mods, saves, and configs must not leak between versions, and (since
    // 2026-10-12, several games at once) saves, settings and configs not
    // between accounts either. The heavy shared files (game jars, libraries,
    // assets, Java) and the version's ONE mods folder stay in the version root;
    // only --gameDir (BasePath) and the natives folder are per account.
    // Core/GameFolders/InstanceLayout.cs has the layout, SharedMods.cs the mods.
    private sealed class GameFolderPath : MinecraftPath
    {
        private readonly string _accountKey;

        public GameFolderPath(string version, string accountKey)
            : base(OriginPaths.VersionRoot(version))
        {
            _accountKey = accountKey;
            // After base(): Library/Versions/Assets/Runtime stay on the shared root.
            BasePath = NormalizePath(OriginPaths.GameDir(version, accountKey));
        }

        // CmlLib wipes + re-extracts natives on every launch; a game already
        // running from a shared folder holds those DLLs open.
        public override string GetNativePath(string id) =>
            NormalizePath(InstanceLayout.NativesDir(Versions, id, _accountKey));
    }

    // Two accounts preparing the SAME version at once would both write its
    // shared files (loader profile, libraries, assets). One provisioning pass
    // per version at a time; the second waits a few seconds, then finds
    // everything in place. Running games are unaffected (they only read).
    private static readonly Dictionary<string, SemaphoreSlim> VersionGates = new(StringComparer.Ordinal);

    private static SemaphoreSlim GateFor(string version)
    {
        lock (VersionGates)
        {
            if (!VersionGates.TryGetValue(version, out var gate))
                VersionGates[version] = gate = new SemaphoreSlim(1, 1);
            return gate;
        }
    }

    // Every launch installs its loader quietly (the Lunar/Feather model — no
    // loader choice exists anywhere): Fabric + the Origin build + the
    // perf/shader stack for modern versions, Forge + OptiFine + the legacy
    // stack for 1.8.9. Caller still has to call Process.Start().
    // progress reports human-readable stage text — drives LaunchLoadingOverlay.
    // accountKey picks the game folder (InstanceLayout.KeyFor); the caller
    // prepares it first (AccountFolders.PrepareGameDir: migration + seeding).
    // profileId = the mod profile (Core/Profiles) whose mods this launch loads
    // INSTEAD of the player's loose jars in the shared folder; null = none.
    public async Task<Process> InstallAndBuildProcessAsync(
        string version, string accountKey, MLaunchOption option,
        bool externalMods = true,
        string? profileId = null,
        IProgress<string>? progress = null, CancellationToken ct = default)
    {
        // A mod profile only ever applies with the player's mods on, and only
        // by an id this launcher made (it becomes a folder name).
        if (!externalMods || !ProfileRules.IsValidId(profileId)) profileId = null;

        var gate = GateFor(version);
        await gate.WaitAsync(ct);
        try
        {
            return await InstallAndBuildCoreAsync(version, accountKey, option, externalMods, profileId, progress, ct);
        }
        finally
        {
            gate.Release();
        }
    }

    private async Task<Process> InstallAndBuildCoreAsync(
        string version, string accountKey, MLaunchOption option,
        bool externalMods, string? profileId,
        IProgress<string>? progress, CancellationToken ct)
    {
        var path = new GameFolderPath(version, accountKey);
        // The shared root: where the install stamp and the loader profile live.
        var sharedRoot = OriginPaths.VersionRoot(version);
        // ONE mods folder per version, shared by every account (Will,
        // 2026-10-12). The game is pointed at it with Fabric's fabric.modsFolder
        // property below, so nothing lives in the account's own mods/ (1.8.9's
        // Forge can't be pointed elsewhere: InstallLegacyAsync mirrors). Several
        // games of this version can be open at once and each holds these jars
        // open: every write below adds a new name, deletes, or swaps a file in
        // with one rename — never rewrites a jar in place (SharedMods.cs).
        var modsFolder = OriginPaths.ModsDir(version);
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

        // LEGACY BRANCH (1.8.9): Forge + OptiFine + the era's perf stack
        // instead of Fabric + Sodium/Iris — see InstallLegacyAsync. Quiet and
        // choiceless exactly like the Fabric path below, which is everything
        // after this line.
        if (LegacyForgeInstaller.IsLegacy(version))
            return await InstallLegacyAsync(version, path, option, externalMods, profileId, progress, ct);

        // Fast path: a stamped instance already resolved this loader and passed
        // a full integrity check under this same launcher build, so both the
        // Fabric meta round-trips and the whole-install rehash are skipped. Any
        // doubt returns Full and behaves exactly like it always did. See
        // InstallVerification for the safety contract.
        var decision = InstallVerification.Evaluate(sharedRoot, version);

        // The cached loader must also read fabric.modsFolder (Loader 0.15.0+),
        // or the game would look in the account's empty mods/ — an older one
        // is replaced by a fresh install, which is always the newest loader.
        string versionName;
        if (decision.CachedVersionName is { } cached && SharedMods.LoaderReadsModsFolderProperty(cached))
        {
            versionName = cached;
        }
        else
        {
            progress?.Report("Installing Fabric loader...");
            var fabricInstaller = new FabricInstaller(SharedHttp);
            versionName = await fabricInstaller.Install(version, path);
        }
        if (!SharedMods.LoaderReadsModsFolderProperty(versionName))
            throw new InvalidOperationException(
                $"{versionName} is too old to load the shared mods folder (needs Fabric Loader {SharedMods.FirstLoaderWithModsFolder}+).");

        var launcher = BuildLauncher(path, decision.FullVerify);

        // Every Fabric mod in play here (Origin Client, and any
        // third-party jar a player drops in) depends on this, so it's
        // installed unconditionally for every Fabric version, not
        // just the one Origin Client itself targets.
        await FabricApiInstaller.InstallAsync(version, modsFolder, progress, ct);

        bool originBundlesPerfStack = false;
        if (OriginBuilds.TryGetValue(version, out var originBuild)
            && InstallOriginJar(originBuild, modsFolder, progress))
        {
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

        // Simple Voice Chat — auto-added to every Fabric instance (henkelmax),
        // downloaded standalone for the EXACT MC version from Modrinth. Skipped for
        // builds that already bundle it jar-in-jar (1.21.1, via originBundlesPerfStack)
        // so Fabric never sees two copies of the mod id. Fail-soft on its own.
        if (!originBundlesPerfStack)
            await SimpleVoiceChatInstaller.InstallAsync(version, modsFolder, progress, ct);

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

            // A twin that would not go is held open by another account's game
            // of this version (the mods folder is shared) — e.g. a catalog bump
            // added the new pin while that game still runs the old one. Two
            // copies would stop THIS game booting, so keep the one in use and
            // drop the rest; the next launch after that game closes swaps them.
            var left = family.Where(File.Exists).ToList();
            if (left.Count > 1)
            {
                var inUse = left.First(f => !ReferenceEquals(f, keep));
                foreach (var other in left.Where(f => !ReferenceEquals(f, inUse)))
                {
                    try { File.Delete(other); } catch { /* locked/removed already */ }
                }
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
        //
        // A MOD PROFILE (Core/Profiles) is the same trick with one more
        // source: Origin's jars from the shared folder PLUS that profile's
        // own jars, and none of the player's loose jars in the shared
        // folder. Its folder is per account, like Origin-only's, so one
        // account's profile game never disturbs another account's game, and
        // the profile's own folder is only ever read (the game holds the
        // copies), so editing or deleting a profile can't hurt a running game.
        var gameModsFolder = modsFolder; // the shared folder, unless Origin-only or a profile
        if (!externalMods || profileId != null)
        {
            progress?.Report(profileId != null ? "Preparing profile mods..." : "Preparing Origin-only mod set...");
            var launchFolder = Path.Combine(path.BasePath,
                profileId != null ? LaunchModFolder.ProfileFolderName : LaunchModFolder.OriginOnlyFolderName);

            // Origin-provisioned = originclient.jar, Fabric API, the
            // perf/shader stack (and, for a profile, Simple Voice Chat).
            // Everything else is the player's and stays out of this launch
            // unless the profile holds it.
            var sharedFiles = Directory.EnumerateFiles(modsFolder);
            var desired = profileId != null
                ? LaunchModFolder.LaunchSet(sharedFiles,
                    Directory.Exists(OriginPaths.ProfileModsDir(version, profileId))
                        ? Directory.EnumerateFiles(OriginPaths.ProfileModsDir(version, profileId))
                        : Enumerable.Empty<string>(),
                    ModManager.IsOriginProvisioned)
                : LaunchModFolder.LaunchSet(sharedFiles, Enumerable.Empty<string>(), ModManager.IsManaged);

            // Diff-synced rather than delete+recreate (see LaunchModFolder.Sync):
            // a still-running game of this account holds these jars open.
            LaunchModFolder.Sync(launchFolder, desired);
            gameModsFolder = launchFolder;
        }

        // Fabric Loader's own supported switch for the folder it loads mods
        // from (checked above: the installed loader reads it).
        var extraJvm = (option.ExtraJvmArguments ?? Enumerable.Empty<MArgument>()).ToList();
        extraJvm.Add(new MArgument(SharedMods.JvmArgument(gameModsFolder)));
        option.ExtraJvmArguments = extraJvm;

        // On the fast path the installer only stats files, so it finishes almost
        // immediately and "Verifying" would flash by; say what is actually
        // happening in each case.
        progress?.Report(decision.FullVerify ? "Verifying game files..." : "Preparing game files...");
        var fileProgress = new InstallProgressThrottle(
            progress, decision.FullVerify ? "Verifying game files" : "Preparing game files").Progress;

        var built = await launcher.InstallAndBuildProcessAsync(versionName, option, fileProgress, null, ct);

        // Only stamp after provisioning came back clean. The stamp is what lets
        // the NEXT launch skip the rehash, so writing it on a failed pass would
        // be exactly the wrong thing.
        InstallVerification.MarkVerified(sharedRoot, version, versionName);
        return built;
    }

    // The legacy (Forge) launch for 1.8.9 — today's model, the same rules as
    // the Fabric path wherever Forge allows it:
    //  * ONE shared mods folder per version (instances/1.8.9/mods/) is where
    //    the launcher installs Origin, OptiFine and the era perf mods, and what
    //    the Mods page edits — exactly like every Fabric version.
    //  * The game folder (--gameDir) is the account's own, so settings, worlds,
    //    servers and configs are per account (config/splash.properties too).
    //  * FML 8.0.x (Forge 11.15) has no fabric.modsFolder: it only loads
    //    <gameDir>/mods. So the shared folder is MIRRORED into the account's
    //    mods/ just before launch (SharedMods.Mirror) — chosen over FML's
    //    --mods / --modListFile launch arguments because those are resolved
    //    relative to the game folder, and whether coremods / tweakers (which is
    //    how OptiFine loads on Forge) are discovered through them in this exact
    //    Forge build can't be verified without booting it; a plain mods/ folder
    //    is what every 1.8.9 Forge setup in the wild uses. The mirror only adds,
    //    deletes or swaps whole files in by rename, and a jar the player drops
    //    into the account's mods/ joins the shared folder instead of being lost.
    //  * "Play with external mods" OFF mirrors only the jars Origin provisions
    //    (ModManager.IsManaged), so the crash window's Origin-only retry works
    //    here too — same contract as the Fabric path's mods-origin-only folder.
    // Self-contained: ends with its own game-file download + process build.
    private async Task<Process> InstallLegacyAsync(
        string version, GameFolderPath path, MLaunchOption option, bool externalMods, string? profileId,
        IProgress<string>? progress, CancellationToken ct)
    {
        var sharedRoot = OriginPaths.VersionRoot(version);
        var modsFolder = OriginPaths.ModsDir(version);
        var configFolder = Path.Combine(path.BasePath, "config");

        // The Forge version JSON (bundled) + universal jar, in the shared root.
        var forgeVersionName = await LegacyForgeInstaller.InstallAsync(version, path, progress, ct);

        if (OriginBuilds.TryGetValue(version, out var legacyBuild))
            InstallOriginJar(legacyBuild, modsFolder, progress);

        // OptiFine is the shader mandate on legacy. Non-fatal on failure —
        // the game still boots (vanilla renderer), and the next launch
        // retries the download.
        var optifineOk = await OptiFineInstaller.InstallAsync(version, modsFolder, progress, ct);
        if (!optifineOk)
            progress?.Report("OptiFine unavailable right now — launching without shaders");

        await LegacyStackInstaller.InstallAsync(version, modsFolder, progress, ct);
        LegacyStackInstaller.SeedSplashTheme(configFolder);
        SanitizeCorruptConfigs(configFolder);

        // A mod profile mirrors Origin's own jars plus the profile's folder —
        // none of the player's loose jars in the shared folder (SharedMods.Mirror).
        progress?.Report(profileId != null ? "Preparing profile mods..."
            : externalMods ? "Preparing mods..." : "Preparing Origin-only mod set...");
        Func<string, bool> include = profileId != null ? static name => ModManager.IsOriginProvisioned(name)
            : externalMods ? static _ => true : static name => ModManager.IsManaged(name);
        SharedMods.Mirror(modsFolder, Path.Combine(path.BasePath, InstanceLayout.ModsFolder), include,
            profileMods: profileId != null ? OriginPaths.ProfileModsDir(version, profileId) : null);

        // Same stamp policy as the Fabric path — the legacy instance carries
        // the same multi-hundred-MB asset + JRE rehash, so it gets the same
        // skip once it has proven clean under this launcher build.
        var decision = InstallVerification.Evaluate(sharedRoot, version);
        var launcher = BuildLauncher(path, decision.FullVerify);

        progress?.Report(decision.FullVerify ? "Verifying game files..." : "Preparing game files...");
        var fileProgress = new InstallProgressThrottle(
            progress, decision.FullVerify ? "Verifying game files" : "Preparing game files").Progress;

        // CmlLib resolves the Forge JSON like any custom version: inheritsFrom
        // 1.8.9 (libraries, LWJGL 2 natives, Mojang's jre-legacy Java 8) and
        // its legacy "minecraftArguments" string, plus option's extra game
        // arguments (a friend's server: --server/--port, JoinArguments).
        var built = await launcher.InstallAndBuildProcessAsync(forgeVersionName, option, fileProgress, null, ct);
        InstallVerification.MarkVerified(sharedRoot, version, forgeVersionName);
        return built;
    }

    // Puts the launcher's bundled Origin jar for this build into a mods folder
    // as originclient.jar, removing any other originclient*.jar first. False
    // when this launcher has no bundled jar for the build (a dev build without
    // the module's Gradle output) — the version then plays without Origin.
    private static bool InstallOriginJar(OriginBuild build, string modsFolder, IProgress<string>? progress)
    {
        var bundledJar = OriginPaths.BundledOriginClientJar(build.JarFileName);
        if (!File.Exists(bundledJar)) return false;

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
        var installedJar = Path.Combine(modsFolder, "originclient.jar");

        // The jar is up to ~14 MB and was byte-copied on EVERY launch. Skip
        // the whole sweep when the installed copy already matches the
        // bundled one on length and write time — the stale-jar problem this
        // guards against only arises when the launcher ships a DIFFERENT
        // jar, which changes both.
        if (IsSameFile(bundledJar, installedJar)) return true;

        // Other names only: originclient.jar itself is swapped below.
        foreach (var file in Directory.EnumerateFiles(modsFolder))
        {
            var fn = Path.GetFileName(file);
            if (fn.StartsWith("originclient", StringComparison.OrdinalIgnoreCase)
                && fn.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase)
                && !fn.Equals("originclient.jar", StringComparison.OrdinalIgnoreCase))
            {
                try { File.Delete(file); } catch { /* locked/removed already */ }
            }
        }
        ReplaceOriginJar(bundledJar, installedJar);
        return true;
    }

    // One client for the whole process. The Fabric installer used to get a
    // brand-new HttpClient per launch, which meant a fresh TLS handshake to
    // meta.fabricmc.net every time and a socket left in TIME_WAIT after.
    private static readonly HttpClient SharedHttp = new();

    /// <summary>
    /// Builds the CmlLib launcher with an integrity policy chosen per launch.
    ///
    /// fullVerify = true  -> SHA-1 every file (first launch, or after a crash /
    ///                       launcher update). This is CmlLib's default.
    /// fullVerify = false -> existence + size only. Note size checking is turned
    ///                       ON here in both modes; CmlLib ships it OFF, so the
    ///                       fast path still catches truncated files.
    /// </summary>
    private static MinecraftLauncher BuildLauncher(MinecraftPath path, bool fullVerify)
    {
        // MUST pass the path INTO CreateDefault. The parameterless overload builds
        // its IVersionLoader bound to the DEFAULT Minecraft path, and assigning
        // parameters.MinecraftPath afterwards does NOT rebind that already-built
        // loader — so the instance's own versions folder is never enumerated and
        // the Fabric loader profile we just installed is invisible. That is exactly
        // the "Cannot find fabric-loader-<x>-<mc> [KeyNotFoundException]" launch
        // failure: the version collection came back with the Mojang manifest only
        // (906 entries, zero local) instead of 907 including the loader profile.
        // Measured both ways against a real instance before changing this.
        var parameters = MinecraftLauncherParameters.CreateDefault(path, SharedHttp);

        var installer = ParallelGameInstaller.CreateAsCoreCount(SharedHttp);
        installer.CheckFileSize = true;
        installer.CheckFileChecksum = fullVerify;
        parameters.GameInstaller = installer;

        return new MinecraftLauncher(parameters);
    }

    private static DateTime Max(DateTime a, DateTime b) => a > b ? a : b;

    // Puts the launcher's bundled Origin jar in place as originclient.jar with
    // one rename, never by rewriting the file: another account's game of this
    // version may be running from it. When that game holds it, the swap fails
    // and this launch uses the jar already there (the same build that game
    // runs); the next launch after it closes installs the new one.
    private static void ReplaceOriginJar(string bundledJar, string installedJar)
    {
        var temp = installedJar + ".download";
        try
        {
            File.Copy(bundledJar, temp, overwrite: true);
            // Stamp the copy with the source's write time so the next launch
            // can recognise it as current (IsSameFile).
            File.SetLastWriteTimeUtc(temp, File.GetLastWriteTimeUtc(bundledJar));
            File.Move(temp, installedJar, overwrite: true);
        }
        catch (Exception ex) when ((ex is IOException or UnauthorizedAccessException) && File.Exists(installedJar))
        {
            try { File.Delete(temp); } catch { /* best-effort cleanup of our own copy */ }
        }
    }

    // Cheap "is this already the same file" test: length plus last-write time,
    // no hashing. Used to skip redundant multi-MB copies. Any doubt (missing
    // file, IO error) answers false so the caller does the copy.
    private static bool IsSameFile(string source, string dest)
    {
        try
        {
            if (!File.Exists(source) || !File.Exists(dest)) return false;
            var a = new FileInfo(source);
            var b = new FileInfo(dest);
            return a.Length == b.Length
                   && a.LastWriteTimeUtc == b.LastWriteTimeUtc;
        }
        catch
        {
            return false;
        }
    }

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
