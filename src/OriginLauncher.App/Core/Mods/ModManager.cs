using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using OriginLauncher.App.Core.Compat;

namespace OriginLauncher.App.Core.Mods;

/// <summary>
/// One entry in a version's <c>mods/</c> folder as shown on the Mods page.
/// <paramref name="FileName"/> is the on-disk name WITHOUT the trailing
/// <c>.disabled</c> (i.e. the enabled form, "foo.jar") — it's the stable
/// identity used for toggle/remove regardless of current enabled state.
/// <paramref name="Checking"/>: a jar named like one of Origin's whose owner is not known yet (its bytes have not been
/// checked in this launcher session). An enabled one is shown read-only (<paramref name="IsManaged"/> true), a
/// turned-off one (IsManaged false) is not shown, until <see cref="ModManager.WarmOwnership"/> has checked it off the
/// UI thread, so Origin's own Fabric API / Sodium (or a leftover Origin set aside) never gets an on/off switch or a
/// Remove button just because nothing was launched yet.
/// <paramref name="DisplayName"/> is the mod's own name from inside the jar ("Xaero's World Map") once
/// <see cref="ModManager.WarmNames"/> has read it, with <paramref name="Version"/> its own version; until then (or for a
/// jar that says nothing) a name made from the file name, and <paramref name="NameUnread"/> is true while a read is due.
/// </summary>
public sealed record ModEntry(string FileName, string DisplayName, bool Enabled, bool IsManaged, bool Checking = false,
    string? Version = null, bool NameUnread = false)
{
    /// <summary>The row's second line: "1.39.2 - XaerosWorldMap_1.39.2_Fabric_1.21.jar", or the file name alone.</summary>
    public string FileLine => Version == null ? FileName : $"{Version} - {FileName}";
}

/// <summary>Result of a file operation. <c>Ok</c> false carries a user-facing
/// <c>Error</c> (e.g. "close Minecraft first") — nothing here ever throws to
/// the UI.</summary>
public readonly record struct ModActionResult(bool Ok, string? Error)
{
    public static readonly ModActionResult Success = new(true, null);
    public static ModActionResult Fail(string error) => new(false, error);
}

/// <summary>
/// Manages the user-facing mod set for a single Minecraft version, over that
/// version's <c>/instances/{version}/mods/</c> folder — ONE folder per version,
/// shared by every account (GameFolders/SharedMods.cs): the same folder
/// <see cref="Versions.VersionManager"/> provisions and points every account's
/// game at on launch. Settings, worlds and configs stay per account.
///
/// On/off is the loader-standard extension rename: an enabled mod is
/// <c>foo.jar</c>, a disabled one is <c>foo.jar.disabled</c> — Fabric/Forge only
/// load files ending in <c>.jar</c>, so a disabled mod is simply never seen by
/// the game. No separate folder, no launch-time copying.
///
/// All matching is done by enumerating every file and filtering with
/// <c>EndsWith</c> in managed code — never a Win32 <c>*.jar</c> glob, whose
/// legacy 3-char-extension quirk would ambiguously also match
/// <c>*.jar.disabled</c> / <c>*.jarx</c>.
/// </summary>
public static partial class ModManager
{
    public const string DisabledSuffix = ".jar.disabled";
    public const string JarSuffix = ".jar";
    private const string DownloadSuffix = ".jar.download";

    // The folder the Mods page edits: the version's mods folder (the same for
    // every account), or — while a mod profile is selected (Core/Profiles) —
    // that profile's own folder. Every operation below takes the same optional
    // profileId, so the page manages whichever one it is showing.
    public static string ModsFolder(string version, string? profileId = null) =>
        profileId == null ? OriginPaths.ModsDir(version) : OriginPaths.ProfileModsDir(version, profileId);

    // Another account may be playing this version right now (the folder is
    // shared), so the blocker is "a game", not "your game".
    private const string CloseGameHint = "close Minecraft (on every account playing this version) and try again.";

    /// <summary>Every mod in the version's folder, enabled and disabled, sorted
    /// by display name. User (unmanaged) entries first so they lead the list.
    /// Never throws — an unreadable folder yields an empty list.</summary>
    public static IReadOnlyList<ModEntry> Enumerate(string version, string? profileId = null) =>
        EnumerateIn(ModsFolder(version, profileId), isProfile: profileId != null);

    /// <summary>
    /// <see cref="Enumerate"/> over a given folder (the unit-tested core). Built-in (read-only) rows are ENABLED jars
    /// with one of Origin's names whose bytes are Origin's; a jar not checked yet is built-in and
    /// <see cref="ModEntry.Checking"/>. A turned-off jar is never built-in: it is not loaded, and Origin's installers put
    /// its own copy back (<see cref="TryRestoreOriginsJar"/>). A turned-off leftover Origin set aside itself (an
    /// "aside" line in the install record that the player has not claimed) is not listed at all; any other turned-off
    /// jar is the player's, with its switch. A profile folder holds only the player's own mods (Origin's come from the
    /// shared folder), so nothing in it is built-in, whatever its bytes.
    /// </summary>
    public static IReadOnlyList<ModEntry> EnumerateIn(string folder, bool isProfile)
    {
        if (!Directory.Exists(folder))
            return Array.Empty<ModEntry>();

        var entries = new List<ModEntry>();
        IEnumerable<string> files;
        try
        {
            files = Directory.EnumerateFiles(folder);
        }
        catch
        {
            return Array.Empty<ModEntry>();
        }

        bool? folderHasAside = null; // read once, and only when a turned-off jar with one of Origin's names is here
        foreach (var path in files)
        {
            var name = Path.GetFileName(path);

            // Interrupted downloads leave a *.jar.download temp — never a mod.
            if (name.EndsWith(DownloadSuffix, StringComparison.OrdinalIgnoreCase))
                continue;

            bool enabled;
            string jarName;
            if (name.EndsWith(DisabledSuffix, StringComparison.OrdinalIgnoreCase))
            {
                enabled = false;
                jarName = name[..^".disabled".Length]; // strip only the trailing .disabled
            }
            else if (name.EndsWith(JarSuffix, StringComparison.OrdinalIgnoreCase))
            {
                enabled = true;
                jarName = name;
            }
            else
            {
                continue; // not a mod file
            }

            // No hashing: this runs on the UI thread. The answer comes from memory (a launch or WarmOwnership hashed
            // the jar) or a cheap mod-id check; a jar not known yet is Checking until WarmOwnership has hashed it.
            var managed = false;
            var checking = false;
            if (enabled && !isProfile && IsManaged(jarName))
            {
                var known = KnownOriginsCopy(path);
                managed = known ?? true;
                checking = known == null;
            }
            else if (!enabled && !isProfile && IsManaged(jarName))
            {
                // A leftover Origin set aside itself (an "aside" line, not claimed by the player) is not the player's
                // mod: it is not listed at all. Not known yet: Checking (not shown) until WarmOwnership hashed it.
                folderHasAside ??= OriginInstallRecord.HasAnySetAside(folder);
                var aside = KnownSetAside(path, folderHasAside.Value);
                if (aside == true) continue;
                checking = aside == null;
            }
            var (display, version, unread) = NameOf(path, jarName);
            entries.Add(new ModEntry(jarName, display, enabled, managed, checking, version, unread));
        }

        return entries
            .OrderBy(e => e.IsManaged)                       // user mods lead
            .ThenBy(e => e.DisplayName, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    /// <summary>Enable/disable a user mod by renaming between "foo.jar" and
    /// "foo.jar.disabled". Idempotent; tolerant of the target already existing
    /// (a prior crash could leave both) — the stale duplicate is overwritten.</summary>
    public static ModActionResult SetEnabled(string version, string fileName, bool enabled, string? profileId = null) =>
        SetEnabledIn(ModsFolder(version, profileId), fileName, enabled);

    /// <summary><see cref="SetEnabled"/> on a given folder (the unit-tested core).</summary>
    public static ModActionResult SetEnabledIn(string folder, string fileName, bool enabled)
    {
        var jarPath = Path.Combine(folder, fileName);
        var disabledPath = jarPath + ".disabled";
        var from = enabled ? disabledPath : jarPath;
        var to = enabled ? jarPath : disabledPath;

        try
        {
            if (!File.Exists(from))
            {
                // Already in the desired state (or hand-edited away) — treat the
                // requested end-state as the source of truth, not an error.
                return File.Exists(to) ? ModActionResult.Success
                    : ModActionResult.Fail($"{PrettyName(fileName)} is no longer in the mods folder.");
            }

            // Both forms on disk (a crash between two toggles): an identical twin is just replaced, but a DIFFERENT
            // jar under the other name is another copy of the player's and is never overwritten by a toggle.
            if (File.Exists(to) && !SameBytes(from, to))
                return ModActionResult.Fail(
                    $"There are two different copies of {PrettyName(fileName)} (one on, one off). Remove one first.");
            File.Move(from, to, overwrite: true);
            // A leftover Origin set aside and the player turned back on is theirs from now on (never set aside again).
            if (enabled) OriginInstallRecord.NoteTurnedOn(to);
            return ModActionResult.Success;
        }
        catch (IOException)
        {
            return ModActionResult.Fail("Couldn't change that mod - " + CloseGameHint);
        }
        catch (UnauthorizedAccessException)
        {
            return ModActionResult.Fail("Couldn't change that mod - " + CloseGameHint);
        }
    }

    /// <summary>Add dropped/selected jars to the version's mods folder (or the profile's), after the shared
    /// compatibility engine (Core/Compat/ModImporter) has looked at them:
    ///   * a jar for another Minecraft version or loader, one that can't be read, or one Origin already includes is
    ///     refused, with the reason in plain words (<see cref="ImportReport.Refused"/>);
    ///   * a jar that is the same mod as one already installed is NOT copied over it: it comes back as a question
    ///     (<see cref="ImportReport.NeedsReplace"/>) and the page asks; call again with the file names the player
    ///     said yes to in <paramref name="confirmedReplace"/> (the old file is kept turned off, never deleted);
    ///   * non-.jar files are skipped; a missing requirement is a note, not a refusal.
    /// Never throws; <see cref="ImportReport.Error"/> is a sentence for the player.</summary>
    public static ImportReport Import(
        string version, IEnumerable<string> paths, string? profileId = null, IReadOnlySet<string>? confirmedReplace = null)
    {
        var context = ImportContextFor(version, profileId, confirmedReplace);
        try
        {
            var report = ModImporter.Import(paths, context);
            // What the new set means at Play, said now: the player's own Sodium can switch Origin's shaders or one of
            // Origin's mods off (Compat/PlayerOverrides), which used to reach only logs/mod-compat-<version>.log.
            return report.Ok && (report.Added > 0 || report.TurnedOn.Count > 0)
                ? report with { Notes = report.Notes.Concat(OverrideNotices(version, profileId)).ToList() }
                : report;
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            // The checker must never make adding a mod impossible: a bug there is an error line, not a crash.
            return new ImportReport(0, Array.Empty<ReplaceOffer>(), Array.Empty<string>(), Array.Empty<string>(),
                "Couldn't check that mod: " + ex.Message);
        }
    }

    /// <summary>The folder an add (or an update) of this version/profile goes into, as the compatibility engine sees it.</summary>
    public static ImportContext ImportContextFor(string version, string? profileId, IReadOnlySet<string>? confirmedReplace = null)
    {
        var shared = ModsFolder(version);
        var platform = LaunchPreflight.PlatformFor(version);
        var willInstall = BuiltInsBeforeFirstPlay(platform, shared);
        return profileId == null
            ? new ImportContext(shared, platform, IsManaged, ConfirmedReplace: confirmedReplace, OriginWillInstall: willInstall)
            : new ImportContext(ModsFolder(version, profileId), platform, IsOriginProvisioned,
                IsProfile: true, OriginFolder: shared, ConfirmedReplace: confirmedReplace, OriginWillInstall: willInstall);
    }

    // Before the first Play of a version its shared folder holds none of Origin's jars yet, so the check only saw the
    // player's own and said "Needs fabric-api" / "Needs sodium" about mods Origin installs itself; players then added
    // their own copy, which wins over Origin's (bug run 11 PROF-3). Until Origin's jars are there, what Origin will
    // install for the version (the bundled jar and the perf catalog: the same BuiltInModSet the import uses) answers
    // those "needs" notes (ImportContext.OriginWillInstall). Only the notes: what may be added stays the folder's call,
    // so a player's own newer copy is judged exactly as after the first Play. Once Origin's jars are there they speak
    // for themselves. Not 1.8.9 (Forge: no Fabric API or Sodium, and its jars' dependencies are advisory).
    private static BuiltInModSet? BuiltInsBeforeFirstPlay(CompatPlatform platform, string shared)
    {
        if (platform.IsLegacyForge || HoldsOriginJar(shared)) return null;
        BuiltInModSet? builtIns = null;
        FindBuiltIns(platform.McVersion, ref builtIns);
        return builtIns;
    }

    private static bool HoldsOriginJar(string folder)
    {
        try
        {
            return Directory.Exists(folder)
                && Directory.EnumerateFiles(folder).Any(path => IsManaged(JarNameOf(path)));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return true; // unreadable: keep the check as it was (no built-ins added)
        }
    }

    /// <summary>What Origin will install for <paramref name="version"/> (ModManager.BuiltIns.cs in the launchers; the unit
    /// tests, which link this file without it, leave it null).</summary>
    static partial void FindBuiltIns(string version, ref BuiltInModSet? builtIns);

    /// <summary>
    /// The player-override notices the next Play of this folder will act on ("Shaders are off for this profile:
    /// ...", "Origin's X is off for this profile: ..."), the same plan the launch makes (VersionManager.PlayerPlanFor,
    /// before any vetted-pair swap). Empty on any failure: a notice is never worth blocking an add.
    /// </summary>
    public static IReadOnlyList<string> OverrideNotices(string version, string? profileId)
    {
        try
        {
            // "Your mods" off: a shared-folder Play loads only Origin's jars, so nothing of the player's replaces them.
            if (profileId == null && !SettingsStore.Load().PlayWithExternalMods) return Array.Empty<string>();
            var shared = ModsFolder(version);
            var plan = profileId != null
                ? LaunchPreflight.OverridesFor(version, shared, ModsFolder(version, profileId),
                    IsOriginProvisioned, IsOriginsCopy, null, CancellationToken.None)
                : LaunchPreflight.SharedOverridesFor(version, shared, IsManaged, IsOriginsCopy, null, CancellationToken.None);
            return plan.Notices;
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            return Array.Empty<string>();
        }
    }

    /// <summary>Delete a mod (whichever enabled/disabled form is on disk).</summary>
    public static ModActionResult Remove(string version, string fileName, string? profileId = null)
    {
        var folder = ModsFolder(version, profileId);
        try
        {
            foreach (var candidate in new[] { Path.Combine(folder, fileName), Path.Combine(folder, fileName + ".disabled") })
                if (File.Exists(candidate))
                    File.Delete(candidate);
            return ModActionResult.Success;
        }
        catch (IOException)
        {
            return ModActionResult.Fail("Couldn't remove that mod - " + CloseGameHint);
        }
        catch (UnauthorizedAccessException)
        {
            return ModActionResult.Fail("Couldn't remove that mod - " + CloseGameHint);
        }
    }

    /// <summary>Open the version's mods folder in Explorer, creating it first so
    /// the click always lands somewhere real even before the first launch.</summary>
    public static ModActionResult OpenFolder(string version, string? profileId = null)
    {
        var folder = ModsFolder(version, profileId);
        try
        {
            Directory.CreateDirectory(folder);
            Process.Start(new ProcessStartInfo { FileName = folder, UseShellExecute = true });
            return ModActionResult.Success;
        }
        catch (Exception ex)
        {
            return ModActionResult.Fail($"Couldn't open the folder: {ex.Message}");
        }
    }

    // ---- classification ---------------------------------------------------

    /// <summary>
    /// True for jars Origin itself provisions into the instance (Origin Client,
    /// Fabric API, OptiFine, and the pinned performance stack — Fabric's, or
    /// the legacy Forge one on 1.8.9). These are shown
    /// read-only on the Mods page — the player can't disable a hard dependency
    /// and break their game. Everything else is a user-owned mod.
    ///
    /// Matches each project's canonical filename SHAPE, not a bare token, so
    /// third-party addons that merely start with the same word survive:
    /// "sodium-extra", "sodiumdynamiclights", "reeses-sodium-options" are NOT
    /// Sodium itself and are left fully under user control.
    /// </summary>
    public static bool IsManaged(string jarName)
    {
        var n = jarName.ToLowerInvariant();
        return n.StartsWith("originclient")
            || n.StartsWith("fabric-api-")
            || n.StartsWith("legacy-fabric-api-")
            || n.Equals("optifine.jar")
            || Loaders.LegacyStackInstaller.IsStackJar(jarName)
            || IsBundledPerfJar(jarName)
            || IsCatalogOnlyPerfJar(jarName);
    }

    /// <summary>
    /// Everything Origin itself puts in a version's mods folder: the managed
    /// set plus Simple Voice Chat (SimpleVoiceChatInstaller adds it to every
    /// Fabric version, but it stays a normal, toggleable row on the Mods page).
    /// A mod-profile launch (Core/Profiles/LaunchModFolder) takes THESE from the
    /// shared folder and everything else from the profile.
    /// </summary>
    public static bool IsOriginProvisioned(string jarName) =>
        IsManaged(jarName) || jarName.StartsWith("voicechat-", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// The performance jars Origin's own catalog installs (Sodium, Indium,
    /// Lithium, FerriteCore, Krypton, Iris). This is the exact set the 1.21.1
    /// launch path must purge when its standalone copies would collide with the
    /// same mods bundled jar-in-jar inside originclient.jar — matched by
    /// canonical filename shape so a leftover from any prior version is caught,
    /// while user addons are spared.
    /// </summary>
    public static bool IsBundledPerfJar(string jarName)
    {
        var n = jarName.ToLowerInvariant();
        return n.StartsWith("sodium-fabric-")
            || n.StartsWith("lithium-fabric-")
            || n.StartsWith("indium-")
            || n.StartsWith("ferritecore-")
            || n.StartsWith("krypton-")
            || n.StartsWith("immediatelyfast-")
            || n.StartsWith("modernfix-fabric-")
            || n.StartsWith("modmenu-")
            || IsIrisJar(n);
    }

    /// <summary>
    /// Perf jars the standalone catalog installs but that no Origin build bundles
    /// jar-in-jar (EntityCulling). They are managed (read-only on the Mods page,
    /// one copy per family so a hand-dropped twin can't cause a duplicate mod id)
    /// but are NOT in <see cref="IsBundledPerfJar"/>: that set is what the 1.21.1
    /// launch path purges, and nothing bundled there collides with these.
    /// </summary>
    public static bool IsCatalogOnlyPerfJar(string jarName) =>
        jarName.StartsWith("entityculling-fabric-", StringComparison.OrdinalIgnoreCase);

    // Iris has shipped THREE canonical filename shapes over the catalog's era
    // range: "iris-fabric-1.8.8+mc1.21.1", "iris-mc1.20-1.6.4", and the plain
    // "iris-1.7.3+mc1.21". The last is "iris-" + digit — that digit check is
    // what keeps genuinely third-party jars like "iris-flywheel-compat" out of
    // the managed set.
    private static bool IsIrisJar(string lowerName) =>
        lowerName.StartsWith("iris-fabric-")
        || lowerName.StartsWith("iris-mc")
        || (lowerName.StartsWith("iris-") && lowerName.Length > 5 && char.IsAsciiDigit(lowerName[5]));

    // The managed mod families, keyed by canonical filename prefix. Iris is
    // handled separately (see IsIrisJar — it has shipped three filename shapes
    // that must all land in one family).
    private static readonly string[] ManagedFamilyPrefixes =
    {
        "originclient",
        "fabric-api-",
        "legacy-fabric-api-",
        "sodium-fabric-",
        "lithium-fabric-",
        "indium-",
        "ferritecore-",
        "krypton-",
        // Bare "immediatelyfast-": the 1.18.2 and 1.19.3 builds are named
        // "ImmediatelyFast-1.1.12+..." (no "Fabric"), so the longer prefix left
        // them unmanaged: toggleable on the Mods page and never de-duplicated.
        "immediatelyfast-",
        "modernfix-fabric-",
        "modmenu-",
        "entityculling-fabric-",
    };

    /// <summary>
    /// Groups a managed jar under its mod-family key so provisioning can keep
    /// exactly ONE enabled copy per family. Fabric loads only the higher version
    /// of a duplicate mod id, so two coexisting copies of e.g. fabric-api (a player
    /// updating it by hand next to the launcher's, or a catalog version bump
    /// leaving the old pin behind) silently replace the pinned copy.
    /// Non-managed names key to themselves (a group of one — never touched).
    /// </summary>
    public static string ModFamilyKey(string jarName)
    {
        var n = jarName.ToLowerInvariant();
        if (IsIrisJar(n))
            return "iris-fabric-"; // all three Iris filename shapes = one family
        foreach (var prefix in ManagedFamilyPrefixes)
            if (n.StartsWith(prefix))
                return prefix;
        return n;
    }

    /// <summary>
    /// Best-effort mod version from a canonical filename: the first dotted
    /// numeric run after the family prefix — "fabric-api-0.116.13+1.21.1.jar"
    /// → 0.116.13, "sodium-fabric-0.6.13+mc1.21.1.jar" → 0.6.13. Null when the
    /// name has no parseable version (e.g. "originclient.jar").
    /// </summary>
    public static Version? TryParseVersion(string jarName)
    {
        var match = System.Text.RegularExpressions.Regex.Match(jarName, @"(\d+(?:\.\d+)+)");
        return match.Success && Version.TryParse(match.Groups[1].Value, out var v) ? v : null;
    }

    // ---- file ownership (Phase 5.2a) ---------------------------------------

    /// <summary>
    /// <see cref="IsManaged"/> for a real file: the name has one of Origin's shapes AND the file is Origin's copy
    /// (<see cref="IsOriginsCopy"/>). A player's jar that only happens to be named like one of Origin's (a renamed
    /// addon, another mod with the same prefix) is the player's: toggleable, never de-duplicated away.
    /// </summary>
    public static bool IsManagedFile(string path) => IsManaged(JarNameOf(path)) && IsOriginsCopy(path);

    /// <summary><see cref="IsOriginProvisioned"/> for a real file (adds Simple Voice Chat), confirmed the same way.</summary>
    public static bool IsOriginProvisionedFile(string path) => IsOriginProvisioned(JarNameOf(path)) && IsOriginsCopy(path);

    /// <summary>
    /// Decides whether a jar whose name looks like one of Origin's really is Origin's, by its BYTES, never its name or
    /// mod id (Will, 2026-10-25: "a player's own mods win" -- his own Sodium sits in the shared folder beside Origin's
    /// and carries the same id, and the old "same id counts as Origin's" rule deleted it every launch):
    ///   1. its SHA-512 is a file Origin pins now or ever pinned (ManagedPins, incl. ManagedPins.History.cs), or one an
    ///      Origin installer wrote into this folder (OriginInstallRecord) -> Origin's;
    ///   2. otherwise it is the player's, even when it is the same mod (a hand update is the player's choice);
    ///   3. a jar that cannot be read right now (locked, half-copied) falls back to its name, and is not remembered.
    ///      Anything that DELETES, renames or replaces a jar asks <see cref="OwnerOf"/> instead, where "not sure" is no.
    /// OptiFine, the legacy Forge stack and originclient (Origin's own name; nothing else is called that) are decided
    /// by name. Only names with Origin's shapes are ever checked (about ten jars, not the player's whole folder), and
    /// the answer is remembered by (path, size, time), so a repeat call costs one stat. Callers on the UI thread pass
    /// <paramref name="allowHash"/> false: then a jar whose mod id is not the family's is the player's, and one whose
    /// id matches is answered from memory (the last launch hashed it) or, unknown, as the player's (shown, never acted on).
    /// </summary>
    public static bool IsOriginsCopy(string path) => IsOriginsCopy(path, allowHash: true);

    /// <summary>
    /// True when <paramref name="modsFolder"/> already holds ORIGIN's copy of the family named by
    /// <paramref name="filePrefix"/> ("fabric-api-", "voicechat-"), on or off (a copy the player turned off is never
    /// fetched again). A player's own jar of that name does not count: Origin's still has to be there for Origin's mods
    /// and for every profile or Origin-only launch (they load Origin's jars only). The installers' skip test.
    /// </summary>
    public static bool HasOriginsCopy(string modsFolder, string filePrefix) =>
        Directory.Exists(modsFolder) && Directory.EnumerateFiles(modsFolder).Any(f =>
        {
            var n = Path.GetFileName(f);
            if (!n.StartsWith(filePrefix, StringComparison.OrdinalIgnoreCase)) return false;
            var on = n.EndsWith(JarSuffix, StringComparison.OrdinalIgnoreCase);
            // A turned-off copy counts only for a family the player may switch off (Simple Voice Chat). A built-in
            // family's turned-off jar (Fabric API) is never a working copy: the Mods page offered a switch on Origin's
            // own Fabric API before it knew whose it was, and counting the ".disabled" left it off for good.
            var off = n.EndsWith(DisabledSuffix, StringComparison.OrdinalIgnoreCase) && !IsManaged(JarNameOf(n));
            return (on || off) && IsOriginsCopy(f);
        });

    /// <summary>
    /// Turns Origin's own built-in <paramref name="fileName"/> back on when only its ".jar.disabled" form is in the folder and
    /// those bytes are Origin's (<paramref name="isPinnedBytes"/>, and not claimed by the player in the install
    /// record). A rename, never a rewrite. Returns true when the jar is on now. Any other turned-off file of that name
    /// is the player's and is left alone. Never throws.
    /// </summary>
    public static bool TryRestoreOriginsJar(string modsFolder, string fileName, Func<string, bool> isPinnedBytes)
    {
        var jar = Path.Combine(modsFolder, fileName);
        var off = jar + ".disabled";
        try
        {
            if (!IsManaged(fileName)) return false; // a family the player may switch off (Voice Chat) stays off
            if (File.Exists(jar) || !File.Exists(off)) return false;
            if (!isPinnedBytes(off) || OwnerOf(off) == JarOwner.Player) return false;
            File.Move(off, jar, overwrite: false);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false; // a running game holds it, or the name was taken meanwhile: left as it is
        }
    }

    /// <summary>
    /// Checks, by their bytes, every enabled jar in <paramref name="modsFolder"/> named like one of Origin's that is
    /// not known yet, so the Mods page can show Origin's own as built-in without a launch first. Hashes about ten
    /// files, so it runs OFF the UI thread (the page calls it through Task.Run). Records the old unpinned Fabric API /
    /// Voice Chat as Origin's first, exactly as the next launch would (<see cref="OriginInstallRecord.AdoptLegacy"/>).
    /// Returns how many jars it decided (0 = nothing new, the page need not rebuild). Never throws.
    /// </summary>
    public static int WarmOwnership(string modsFolder)
    {
        var decided = 0;
        try
        {
            if (!Directory.Exists(modsFolder)) return 0;
            OriginInstallRecord.AdoptLegacy(modsFolder);
            var hasAside = OriginInstallRecord.HasAnySetAside(modsFolder);
            foreach (var path in Directory.EnumerateFiles(modsFolder).ToList())
            {
                var name = Path.GetFileName(path);
                if (name.EndsWith(DisabledSuffix, StringComparison.OrdinalIgnoreCase) && IsManaged(JarNameOf(name)))
                {
                    // A turned-off one: is it a leftover Origin set aside itself (then the page does not list it)?
                    if (KnownSetAside(path, hasAside) != null) continue;
                    if (OriginInstallRecord.Sha512Of(path) is not { } sha) continue; // locked right now: stays Checking
                    var file = new FileInfo(path);
                    RememberAside(file.FullName, (file.Length, file.LastWriteTimeUtc.Ticks),
                        OriginInstallRecord.IsSetAside(modsFolder, sha));
                    decided++;
                    continue;
                }
                if (!name.EndsWith(JarSuffix, StringComparison.OrdinalIgnoreCase) || !IsManaged(name)) continue;
                if (KnownOriginsCopy(path) != null) continue;
                IsOriginsCopy(path, allowHash: true); // hashes and remembers (unless the jar can't be read right now)
                if (KnownOriginsCopy(path) != null) decided++;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* best-effort: rows stay Checking */ }
        return decided;
    }

    // Whether a turned-off jar is a leftover Origin set aside itself, without hashing: false when the folder's record has
    // no "aside" line at all, else remembered by (path, size, time) from WarmOwnership. Null = only the bytes can say.
    internal static bool? KnownSetAside(string path, bool folderHasAside)
    {
        if (!folderHasAside) return false;
        try
        {
            var file = new FileInfo(path);
            if (!file.Exists) return false;
            return SetAside.TryGetValue(file.FullName, out var hit) && hit.Stamp == (file.Length, file.LastWriteTimeUtc.Ticks)
                ? hit.Aside
                : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return null;
        }
    }

    private static void RememberAside(string key, (long, long) stamp, bool aside)
    {
        if (SetAside.Count >= OwnershipCacheLimit) SetAside.Clear();
        SetAside[key] = (stamp, aside);
    }

    private static readonly ConcurrentDictionary<string, ((long, long) Stamp, bool Aside)> SetAside =
        new(StringComparer.OrdinalIgnoreCase);

    // The owner without hashing: remembered, by name (originclient, OptiFine, legacy stack) or by a cheap mod-id
    // mismatch (certainly the player's). Null = only the bytes can say (WarmOwnership). A missing file is not Origin's.
    internal static bool? KnownOriginsCopy(string path)
    {
        FileInfo file;
        try
        {
            file = new FileInfo(path);
            if (!file.Exists) return false;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return null;
        }
        var stamp = (file.Length, file.LastWriteTimeUtc.Ticks);
        if (Ownership.TryGetValue(file.FullName, out var hit) && hit.Stamp == stamp) return hit.Owner != JarOwner.Player;
        if (Decide(file.FullName, JarNameOf(file.Name), allowHash: false) is not { } owner) return null;
        Remember(file.FullName, stamp, owner);
        return owner != JarOwner.Player;
    }

    /// <summary>
    /// True only when the file is there and is certainly Origin's (step 1 above). A missing, locked or unreadable jar
    /// is never Origin's here. Cleanups that remove a jar ask <see cref="OwnerOf"/>, which also says HOW it is known.
    /// </summary>
    public static bool IsOriginsOwnJar(string path) => OwnerOf(path) != JarOwner.Player;

    /// <summary>
    /// Who a jar in a mods folder belongs to, strictly (missing, locked or unreadable = the player's), and how sure:
    /// <see cref="JarOwner.OriginInstalled"/> only when this folder's install record says an Origin installer wrote
    /// these bytes here (or the name is Origin's own: originclient, OptiFine, the legacy stack); a match with a pin
    /// alone (current or past) or an adopted pre-record jar is <see cref="JarOwner.OriginByPin"/>, because a player's
    /// own download of a version Origin once pinned has exactly the same bytes. Only OriginInstalled may be deleted;
    /// OriginByPin is at most renamed aside (".jar.disabled", bytes kept). A jar the player turned back on after
    /// Origin set it aside is theirs (OriginInstallRecord "player" line).
    /// </summary>
    public static JarOwner OwnerOf(string path)
    {
        FileInfo file;
        try
        {
            file = new FileInfo(path);
            if (!file.Exists) return JarOwner.Player;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return JarOwner.Player;
        }
        var stamp = (file.Length, file.LastWriteTimeUtc.Ticks);
        if (Ownership.TryGetValue(file.FullName, out var hit) && hit.Stamp == stamp) return hit.Owner;
        var decided = Decide(file.FullName, JarNameOf(file.Name), allowHash: true);
        if (decided is not { } owner) return JarOwner.Player;
        Remember(file.FullName, stamp, owner);
        return owner;
    }

    public static bool IsOriginsCopy(string path, bool allowHash)
    {
        FileInfo file;
        try
        {
            file = new FileInfo(path);
            if (!file.Exists) return true; // nothing to read: the name decides, as before
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return true;
        }

        var key = file.FullName;
        var stamp = (file.Length, file.LastWriteTimeUtc.Ticks);
        if (Ownership.TryGetValue(key, out var hit) && hit.Stamp == stamp) return hit.Owner != JarOwner.Player;

        var decided = Decide(file.FullName, JarNameOf(file.Name), allowHash);
        // Not known yet without hashing (the Mods page on the UI thread): the player's, shown as theirs until a launch
        // hashes it. Never Origin's on a guess: that hid Will's own Sodium as an Origin row. Nothing is remembered.
        if (decided is not { } owner) return allowHash;
        Remember(key, stamp, owner);
        return owner != JarOwner.Player;
    }

    private static void Remember(string key, (long, long) stamp, JarOwner owner)
    {
        if (Ownership.Count >= OwnershipCacheLimit) Ownership.Clear();
        Ownership[key] = (stamp, owner);
    }

    private const int OwnershipCacheLimit = 512;
    private static readonly ConcurrentDictionary<string, ((long, long) Stamp, JarOwner Owner)> Ownership =
        new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Forgets remembered answers (tests that rewrite a file inside one clock tick).</summary>
    public static void ClearOwnershipCache()
    {
        Ownership.Clear();
        SetAside.Clear(); // a new record line ("aside", "player") may change these answers too
    }

    // Decided by the bytes; null when the jar could not be read (or, without hashing, could be either).
    private static JarOwner? Decide(string path, string jarName, bool allowHash)
    {
        if (IsOriginOwnName(jarName)) return JarOwner.OriginInstalled; // originclient / OptiFine / legacy stack

        if (!allowHash)
        {
            // Cheap negative only: a jar carrying another mod's id is certainly not Origin's copy of this family.
            if (ExpectedIds(jarName) is not { } ids) return null; // a family with no known id: only the bytes can say
            var info = ModFolderScanner.ReadInfo(path, readNested: false); // the top-level id is all this needs
            if (info == null || info.ReadFailed || info.Id == null) return null;
            return ids.Contains(info.Id) || info.Provides.Any(ids.Contains) ? null : JarOwner.Player;
        }

        if (OriginInstallRecord.Sha512Of(path) is not { } sha) return null;
        var folder = Path.GetDirectoryName(path);
        var entry = folder == null ? OriginInstallRecord.Entry.None : OriginInstallRecord.Lookup(folder, sha);
        return entry switch
        {
            OriginInstallRecord.Entry.Player => JarOwner.Player,
            OriginInstallRecord.Entry.Installed => JarOwner.OriginInstalled,
            OriginInstallRecord.Entry.Adopted => JarOwner.OriginByPin,
            _ => Loaders.ManagedPins.IsKnownSha512(sha) ? JarOwner.OriginByPin : JarOwner.Player,
        };
    }

    // Origin's own file names, decided by name (nothing else is called that; these jars carry no family to compare).
    private static bool IsOriginOwnName(string jarName)
    {
        var n = jarName.ToLowerInvariant();
        return n.StartsWith("originclient") || n.Equals("optifine.jar") || Loaders.LegacyStackInstaller.IsStackJar(jarName);
    }

    // The mod id each of Origin's filename families must carry, or null for a family without a known id.
    private static HashSet<string>? ExpectedIds(string jarName)
    {
        var n = jarName.ToLowerInvariant();
        if (n.StartsWith("voicechat-")) return Ids("voicechat");
        return ModFamilyKey(jarName) switch
        {
            "fabric-api-" => Ids("fabric-api", "fabric"), // the 1.16-era Fabric API's id was "fabric"
            "legacy-fabric-api-" => Ids("legacy-fabric-api"),
            "sodium-fabric-" => Ids("sodium"),
            "lithium-fabric-" => Ids("lithium"),
            "indium-" => Ids("indium"),
            "ferritecore-" => Ids("ferritecore"),
            "krypton-" => Ids("krypton"),
            "immediatelyfast-" => Ids("immediatelyfast"),
            "modernfix-fabric-" => Ids("modernfix"),
            "modmenu-" => Ids("modmenu"),
            "entityculling-fabric-" => Ids("entityculling"),
            "iris-fabric-" => Ids("iris"),
            _ => null,
        };
    }

    private static HashSet<string> Ids(params string[] ids) => new(ids, StringComparer.Ordinal);

    // "foo.jar.disabled" -> "foo.jar"; a full path -> its file name.
    private static string JarNameOf(string path)
    {
        var name = Path.GetFileName(path);
        return name.EndsWith(DisabledSuffix, StringComparison.OrdinalIgnoreCase) ? name[..^".disabled".Length] : name;
    }

    private static bool SameBytes(string a, string b)
    {
        var fa = new FileInfo(a);
        var fb = new FileInfo(b);
        if (fa.Length != fb.Length) return false;
        using var sa = File.OpenRead(a);
        using var sb = File.OpenRead(b);
        return SHA256.HashData(sa).AsSpan().SequenceEqual(SHA256.HashData(sb));
    }

    // ---- names from inside the jars (bug run 11 PROF-5) ----
    // The Mods page showed "litematica-fabric-1.21.1-0.19.50" over "litematica-fabric-1.21.1-0.19.50.jar", and built-in
    // cards "fabric-api-0.116.13+1.21.1", while the Replace prompt used the mod's real name. The name and version come
    // from the jar's own fabric.mod.json / mcmod.info, read OFF the UI thread (WarmNames) and remembered by path + size +
    // time (ModFolderScanner's cache); Enumerate, on the UI thread, only looks them up.

    // Jars that were read and said nothing usable, by "path|size|time": not read again on every refresh.
    private static readonly ConcurrentDictionary<string, byte> Nameless = new(StringComparer.OrdinalIgnoreCase);

    private static (string Display, string? Version, bool Unread) NameOf(string path, string jarName)
    {
        if (ModFolderScanner.CachedInfo(path) is { ReadFailed: false } info && !string.IsNullOrWhiteSpace(info.Name))
            return (info.Name.Trim(), string.IsNullOrWhiteSpace(info.Version) ? null : info.Version.Trim(), false);
        var key = StampKey(path);
        return (PrettyName(jarName), null, key != null && !Nameless.ContainsKey(key));
    }

    // "path|size|time", or null when the file can't be stat'ed.
    private static string? StampKey(string path)
    {
        try
        {
            var file = new FileInfo(path);
            return file.Exists ? $"{path}|{file.Length}|{file.LastWriteTimeUtc.Ticks}" : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return null;
        }
    }

    /// <summary>
    /// Reads the names of the jars in <paramref name="folder"/> that have not been read yet (the Mods page calls it off
    /// the UI thread when a row is <see cref="ModEntry.NameUnread"/>, then rebuilds). Returns how many got a real name.
    /// Never throws.
    /// </summary>
    public static int WarmNames(string folder)
    {
        var named = 0;
        try
        {
            foreach (var path in Directory.EnumerateFiles(folder))
            {
                var name = Path.GetFileName(path);
                if (!name.EndsWith(JarSuffix, StringComparison.OrdinalIgnoreCase) && !name.EndsWith(DisabledSuffix, StringComparison.OrdinalIgnoreCase)) continue;
                if (!NameOf(path, JarNameOf(path)).Unread) continue;
                var info = ModFolderScanner.ReadInfo(path, readNested: false);
                if (info is { ReadFailed: false } && !string.IsNullOrWhiteSpace(info.Name)) named++;
                else if (info != null && StampKey(path) is { } key) Nameless[key] = 0; // said nothing: final
                // null: couldn't open it right now (a game holds it): asked again on the next refresh
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* the rows keep their file names */ }
        return named;
    }

    // Turn "sodium-fabric-0.6.13+mc1.21.1.jar" into "sodium-fabric-0.6.13+mc1.21.1": the fallback name for a jar that
    // has not been read yet or says nothing. Purely cosmetic; the real identity is always the filename.
    private static string PrettyName(string jarName)
    {
        var name = jarName.EndsWith(JarSuffix, StringComparison.OrdinalIgnoreCase)
            ? jarName[..^JarSuffix.Length]
            : jarName;
        return name.Replace('_', ' ');
    }
}

/// <summary>Who a jar in a mods folder belongs to (<see cref="ModManager.OwnerOf"/>).</summary>
public enum JarOwner
{
    /// <summary>The player's (or not known for sure): never deleted, renamed or replaced by the launcher.</summary>
    Player,

    /// <summary>Origin's by a pin match or adoption alone: may be set aside (renamed to .disabled), never deleted.</summary>
    OriginByPin,

    /// <summary>An Origin installer recorded writing exactly these bytes into this folder: may be deleted.</summary>
    OriginInstalled,
}
