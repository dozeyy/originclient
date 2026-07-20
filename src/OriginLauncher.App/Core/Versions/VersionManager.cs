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
            // 1.21.5 (src/mods/versions/1.21.5) — the GuiGraphics.blit-rework +
            // HitboxRenderState era (still setShaderColor + PoseStack transforms).
            // Its own mapped build; boot-verified clean (2026-07-13). Will asked
            // for the popular gap versions (1.21.5, 1.21.8) specifically.
            ["1.21.5"] = new("originclient-1.21.5.jar", BundlesPerfStack: false),
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
            // --- LEGACY (Forge + OptiFine) — added 2026-07-14 ---
            // 1.8.9 and 1.12.2 are the pre-Fabric era: Fabric never supported
            // them, and OptiFine is the only shader/perf layer that exists
            // there — so these two versions (and ONLY these) install Forge +
            // OptiFine instead of Fabric + Sodium/Iris, silently, exactly like
            // the Fabric path (the Lunar model: the player never sees a loader
            // choice anywhere). Each ships its own from-scratch Origin build
            // (src/mods/versions/1.8.9, /1.12.2 — Forge events, no mixins).
            // BundlesPerfStack is inert for legacy entries: the legacy install
            // branch never consults the Fabric perf catalog at all.
            // 1.8.9 — DISABLED 2026-07-16 (Will's call: deprioritized, not being
            // worked for a while). Commenting it out of OriginBuilds is the whole
            // grey-out: OriginSupportedVersions drops it, so VersionCatalog marks
            // it unsupported -> the 1.8 card shows "Coming Soon" and can't launch,
            // and any stale 1.8.9 selection falls back to the default version. The
            // 1.8.9 module + legacy install code stay in place, ready to re-enable
            // by uncommenting this line.
            // ["1.8.9"]  = new("originclient-1.8.9.jar",  BundlesPerfStack: false),
            // 1.12.2 — DISABLED 2026-07-16 (Will's call: greyed out alongside 1.8.9).
            // Same one-line grey-out: commenting it out of OriginBuilds drops it from
            // OriginSupportedVersions -> VersionCatalog marks it unsupported -> the
            // 1.12 card shows "Coming Soon" and can't launch, stale selections fall
            // back to default. Module + legacy install code stay, re-enable by
            // uncommenting. (JEI-on-legacy-Forge is therefore moot until re-enabled.)
            // ["1.12.2"] = new("originclient-1.12.2.jar", BundlesPerfStack: false),
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

    // Every version gets its own root under /instances/{version}/ — hard
    // requirement (see CLAUDE.md): mods, saves, and configs must not leak
    // between versions.
    private static MinecraftPath BuildInstancePath(string version) =>
        new(Path.Combine(OriginPaths.Instances, version));

    // Every launch installs its loader quietly (the Lunar/Feather model — no
    // loader choice exists anywhere): Fabric + the Origin build + the
    // perf/shader stack for modern versions, Forge + OptiFine + the legacy
    // stack for 1.8.9/1.12.2. Caller still has to call Process.Start().
    // progress reports human-readable stage text — drives LaunchLoadingOverlay.
    public async Task<Process> InstallAndBuildProcessAsync(
        string version, MLaunchOption option,
        bool externalMods = true,
        bool chunkMultithreading = false, bool fastLightEngine = false,
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

        // LEGACY BRANCH (1.8.9 / 1.12.2): Forge + OptiFine + the era's perf
        // stack instead of Fabric + Sodium/Iris — the pre-Fabric versions have
        // no Fabric option and OptiFine is their only shader layer. Quiet and
        // choiceless exactly like the Fabric path below. Self-contained: the
        // branch finishes with its own game-file download + process build and
        // returns — the code after it is the Fabric path.
        if (LegacyForgeInstaller.IsLegacy(version))
        {
            var forgeVersionName = await LegacyForgeInstaller.InstallAsync(version, path, progress, ct);

            if (OriginBuilds.TryGetValue(version, out var legacyBuild)
                && File.Exists(OriginPaths.BundledOriginClientJar(legacyBuild.JarFileName)))
            {
                progress?.Report("Installing Origin Client...");
                // Same stale-jar sweep as the Fabric path: always ship the
                // launcher's own bundled build, whatever an older launcher left.
                foreach (var file in Directory.EnumerateFiles(modsFolder))
                {
                    var fn = Path.GetFileName(file);
                    if (fn.StartsWith("originclient", StringComparison.OrdinalIgnoreCase)
                        && fn.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase))
                    {
                        try { File.Delete(file); } catch { /* locked/removed already */ }
                    }
                }
                File.Copy(OriginPaths.BundledOriginClientJar(legacyBuild.JarFileName),
                          Path.Combine(modsFolder, "originclient.jar"), overwrite: true);
            }

            // OptiFine is the shader mandate on legacy. Non-fatal on failure —
            // the game still boots (vanilla renderer), and the next launch
            // retries the download.
            var optifineOk = await OptiFineInstaller.InstallAsync(version, modsFolder, progress, ct);
            if (!optifineOk)
                progress?.Report("OptiFine unavailable right now — launching without shaders");

            await LegacyStackInstaller.InstallAsync(version, modsFolder, progress, ct);
            LegacyStackInstaller.SeedSplashTheme(configFolder);
            SanitizeCorruptConfigs(configFolder);

            // NOTE: the Fabric-only conveniences don't apply here — the
            // fabric.modsFolder Origin-only launch and the Fabric-family
            // dedupe both key on Fabric loader semantics. "Play with external
            // mods" is treated as always-on for legacy instances.
            progress?.Report("Downloading game files...");
            var legacyFileProgress = new Progress<InstallerProgressChangedEventArgs>(e =>
                progress?.Report($"Downloading game files ({e.ProgressedTasks}/{e.TotalTasks})"));
            return await launcher.InstallAndBuildProcessAsync(forgeVersionName, option, legacyFileProgress, null, ct);
        }

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
            // like sodiumdynamiclights / reeses-sodium-options are spared
            // (the old bare "sodium" prefix silently deleted those every
            // launch). Sodium Extra is NO LONGER spared: it joined the
            // managed extras stack 2026-07-20, so a hand-installed copy is
            // purged/de-duped like any other managed family now.
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

        // Opt-in experimental perf mods (Settings -> Performance): C2ME (chunk
        // multithreading) and Starlight/ScalableLux (fast light engine). These
        // are ALWAYS standalone — never bundled — so unlike the core stack they
        // apply on 1.21.1 too; the catalog line exists there even though
        // perfProfile is forced null for the bundled case, so fetch it directly.
        // ON  -> download the enabled families (InstallOptionalAsync filters).
        // OFF -> delete any copy left from a previous "on" launch, so toggling
        //        the switch off actually removes the mod. The sweep is scoped to
        //        the exact managed family (IsC2meJar / IsLightEngineJar) — the
        //        same discipline the rest of the managed stack uses — and covers
        //        both the enabled ".jar" and a ".jar.disabled" twin. Runs BEFORE
        //        the family de-dupe and the origin-only rebuild below, so both
        //        see the corrected mods/ folder.
        var optionalProfile = PerformanceModCatalog.TryGet(version);
        if (optionalProfile?.Optional is { Count: > 0 })
            await PerfModInstaller.InstallOptionalAsync(
                optionalProfile, modsFolder, chunkMultithreading, fastLightEngine, progress, ct);
        foreach (var file in Directory.EnumerateFiles(modsFolder))
        {
            var name = Path.GetFileName(file);
            var isC2me = ModManager.IsC2meJar(name);
            var isLight = ModManager.IsLightEngineJar(name);
            if ((isC2me && !chunkMultithreading) || (isLight && !fastLightEngine))
            {
                try { File.Delete(file); } catch { /* locked/removed already */ }
            }
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
        // Enabled opt-in pins win their family too (the catalog build is the one
        // to keep over a hand-dropped copy). Uses optionalProfile, which is
        // populated even on 1.21.1 where perfProfile is null.
        if (optionalProfile?.Optional is { } opt)
            foreach (var mod in opt)
                if ((ModManager.IsC2meJar(mod.FileName) && chunkMultithreading)
                    || (ModManager.IsLightEngineJar(mod.FileName) && fastLightEngine))
                    pinnedJarNames.Add(mod.FileName);

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
