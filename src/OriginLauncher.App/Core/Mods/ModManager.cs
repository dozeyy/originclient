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
/// </summary>
public sealed record ModEntry(string FileName, string DisplayName, bool Enabled, bool IsManaged);

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
public static class ModManager
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
    public static IReadOnlyList<ModEntry> Enumerate(string version, string? profileId = null)
    {
        var folder = ModsFolder(version, profileId);
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

            entries.Add(new ModEntry(
                jarName,
                PrettyName(jarName),
                enabled,
                // No hashing: this runs on the UI thread. The last launch hashed these jars, so the answer
                // normally comes from memory; see IsOriginsCopy for the rest.
                IsManaged(jarName) && IsOriginsCopy(path, allowHash: false)));
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
        var shared = ModsFolder(version);
        var context = profileId == null
            ? new ImportContext(shared, LaunchPreflight.PlatformFor(version), IsManaged, ConfirmedReplace: confirmedReplace)
            : new ImportContext(ModsFolder(version, profileId), LaunchPreflight.PlatformFor(version), IsOriginProvisioned,
                IsProfile: true, OriginFolder: shared, ConfirmedReplace: confirmedReplace);
        try
        {
            return ModImporter.Import(paths, context);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            // The checker must never make adding a mod impossible: a bug there is an error line, not a crash.
            return new ImportReport(0, Array.Empty<ReplaceOffer>(), Array.Empty<string>(), Array.Empty<string>(),
                "Couldn't check that mod: " + ex.Message);
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
    ///      Anything that DELETES or replaces a jar asks <see cref="IsOriginsOwnJar"/> instead, where "not sure" is no.
    /// OptiFine, the legacy Forge stack and originclient (Origin's own name; nothing else is called that) are decided
    /// by name. Only names with Origin's shapes are ever checked (about ten jars, not the player's whole folder), and
    /// the answer is remembered by (path, size, time), so a repeat call costs one stat. Callers on the UI thread pass
    /// <paramref name="allowHash"/> false: then a jar whose mod id is not the family's is the player's, and one whose
    /// id matches is answered from memory (the last launch hashed it) or, unknown, by its name.
    /// </summary>
    public static bool IsOriginsCopy(string path) => IsOriginsCopy(path, allowHash: true);

    /// <summary>
    /// True only when the file is there and is certainly Origin's (step 1 above). The test every cleanup that deletes,
    /// renames or replaces a jar in a mods folder must pass: a missing, locked or unreadable jar is never Origin's here.
    /// </summary>
    public static bool IsOriginsOwnJar(string path)
    {
        FileInfo file;
        try
        {
            file = new FileInfo(path);
            if (!file.Exists) return false;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return false;
        }
        var stamp = (file.Length, file.LastWriteTimeUtc.Ticks);
        if (Ownership.TryGetValue(file.FullName, out var hit) && hit.Stamp == stamp) return hit.Origin;
        var decided = Decide(file.FullName, JarNameOf(file.Name), allowHash: true);
        if (decided is not { } origin) return false;
        Remember(file.FullName, stamp, origin);
        return origin;
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
        if (Ownership.TryGetValue(key, out var hit) && hit.Stamp == stamp) return hit.Origin;

        bool? decided = Decide(file.FullName, JarNameOf(file.Name), allowHash);
        if (decided is not { } origin) return true; // unreadable: the name decides, and nothing is remembered
        Remember(key, stamp, origin);
        return origin;
    }

    private static void Remember(string key, (long, long) stamp, bool origin)
    {
        if (Ownership.Count >= OwnershipCacheLimit) Ownership.Clear();
        Ownership[key] = (stamp, origin);
    }

    private const int OwnershipCacheLimit = 512;
    private static readonly ConcurrentDictionary<string, ((long, long) Stamp, bool Origin)> Ownership =
        new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Forgets remembered answers (tests that rewrite a file inside one clock tick).</summary>
    public static void ClearOwnershipCache() => Ownership.Clear();

    // True/false when the bytes decide; null when the jar could not be read (or, without hashing, could be either).
    private static bool? Decide(string path, string jarName, bool allowHash)
    {
        var ids = ExpectedIds(jarName);
        if (ids == null) return true; // originclient / OptiFine / legacy stack: Origin's own names

        if (!allowHash)
        {
            // Cheap negative only: a jar carrying another mod's id is certainly not Origin's copy of this family.
            var info = ModFolderScanner.ReadInfo(path, readNested: false); // the top-level id is all this needs
            if (info == null || info.ReadFailed || info.Id == null) return null;
            return ids.Contains(info.Id) || info.Provides.Any(ids.Contains) ? null : false;
        }

        try
        {
            string sha;
            using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                sha = Convert.ToHexString(SHA512.HashData(stream));
            return Loaders.ManagedPins.IsKnownSha512(sha)
                || (Path.GetDirectoryName(path) is { } folder && OriginInstallRecord.Contains(folder, sha));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    // The mod id each of Origin's filename families must carry, or null where the jar has no Fabric metadata.
    private static HashSet<string>? ExpectedIds(string jarName)
    {
        var n = jarName.ToLowerInvariant();
        if (n.StartsWith("voicechat-")) return Ids("voicechat");
        if (n.StartsWith("originclient") || n.Equals("optifine.jar") || Loaders.LegacyStackInstaller.IsStackJar(jarName)) return null;
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

    // Turn "sodium-fabric-0.6.13+mc1.21.1.jar" into "sodium-fabric 0.6.13".
    // Purely cosmetic; the real identity is always the filename.
    private static string PrettyName(string jarName)
    {
        var name = jarName.EndsWith(JarSuffix, StringComparison.OrdinalIgnoreCase)
            ? jarName[..^JarSuffix.Length]
            : jarName;
        return name.Replace('_', ' ');
    }
}
