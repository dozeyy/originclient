using System.Diagnostics;
using System.IO;

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
/// version's isolated <c>/instances/{version}/mods/</c> folder (the same folder
/// <see cref="Versions.VersionManager"/> provisions on launch).
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

    public static string ModsFolder(string version) =>
        Path.Combine(OriginPaths.Instances, version, "mods");

    /// <summary>Every mod in the version's folder, enabled and disabled, sorted
    /// by display name. User (unmanaged) entries first so they lead the list.
    /// Never throws — an unreadable folder yields an empty list.</summary>
    public static IReadOnlyList<ModEntry> Enumerate(string version)
    {
        var folder = ModsFolder(version);
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
                IsManaged(jarName)));
        }

        return entries
            .OrderBy(e => e.IsManaged)                       // user mods lead
            .ThenBy(e => e.DisplayName, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    /// <summary>Enable/disable a user mod by renaming between "foo.jar" and
    /// "foo.jar.disabled". Idempotent; tolerant of the target already existing
    /// (a prior crash could leave both) — the stale duplicate is overwritten.</summary>
    public static ModActionResult SetEnabled(string version, string fileName, bool enabled)
    {
        var folder = ModsFolder(version);
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

            File.Move(from, to, overwrite: true);
            return ModActionResult.Success;
        }
        catch (IOException)
        {
            return ModActionResult.Fail("Couldn't change that mod — close Minecraft and try again.");
        }
        catch (UnauthorizedAccessException)
        {
            return ModActionResult.Fail("Couldn't change that mod — close Minecraft and try again.");
        }
    }

    /// <summary>Copy dropped/selected files into the version's mods folder.
    /// Non-.jar files are skipped (reported back). Importing "foo.jar" also
    /// clears any existing "foo.jar.disabled" so the import always lands enabled
    /// rather than silently colliding with a disabled twin.</summary>
    public static ModActionResult Import(string version, IEnumerable<string> paths)
    {
        var folder = ModsFolder(version);
        try
        {
            Directory.CreateDirectory(folder);
        }
        catch (Exception ex)
        {
            return ModActionResult.Fail($"Couldn't open the mods folder: {ex.Message}");
        }

        int imported = 0, skipped = 0;
        foreach (var src in paths)
        {
            if (!src.EndsWith(JarSuffix, StringComparison.OrdinalIgnoreCase) || !File.Exists(src))
            {
                skipped++;
                continue;
            }

            var name = Path.GetFileName(src);
            var dest = Path.Combine(folder, name);
            try
            {
                File.Copy(src, dest, overwrite: true);
                var disabledTwin = dest + ".disabled";
                if (File.Exists(disabledTwin))
                    File.Delete(disabledTwin);
                imported++;
            }
            catch (IOException)
            {
                return ModActionResult.Fail("Couldn't add that mod — close Minecraft and try again.");
            }
            catch (UnauthorizedAccessException)
            {
                return ModActionResult.Fail("Couldn't add that mod — close Minecraft and try again.");
            }
        }

        if (imported == 0)
            return ModActionResult.Fail(
                skipped > 0 ? "Only .jar mod files can be added." : "No mods to add.");

        return ModActionResult.Success;
    }

    /// <summary>Delete a mod (whichever enabled/disabled form is on disk).</summary>
    public static ModActionResult Remove(string version, string fileName)
    {
        var folder = ModsFolder(version);
        try
        {
            foreach (var candidate in new[] { Path.Combine(folder, fileName), Path.Combine(folder, fileName + ".disabled") })
                if (File.Exists(candidate))
                    File.Delete(candidate);
            return ModActionResult.Success;
        }
        catch (IOException)
        {
            return ModActionResult.Fail("Couldn't remove that mod — close Minecraft and try again.");
        }
        catch (UnauthorizedAccessException)
        {
            return ModActionResult.Fail("Couldn't remove that mod — close Minecraft and try again.");
        }
    }

    /// <summary>Open the version's mods folder in Explorer, creating it first so
    /// the click always lands somewhere real even before the first launch.</summary>
    public static ModActionResult OpenFolder(string version)
    {
        var folder = ModsFolder(version);
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
    /// Fabric API, OptiFine, and the pinned performance stack). These are shown
    /// read-only on the Mods page — the player can't disable a hard dependency
    /// and break their game. Everything else is a user-owned mod.
    ///
    /// Matches each project's canonical filename SHAPE, not a bare token, so
    /// third-party addons that merely start with the same word survive:
    /// "sodiumdynamiclights" and "reeses-sodium-options" are NOT Sodium itself
    /// and are left fully under user control. (Sodium Extra used to be the
    /// canonical example here — it flipped to launcher-managed 2026-07-20 on
    /// Will's direction, when the extras stack made it part of every version.)
    /// </summary>
    public static bool IsManaged(string jarName)
    {
        var n = jarName.ToLowerInvariant();
        return n.StartsWith("originclient")
            || n.StartsWith("fabric-api-")
            || n.StartsWith("legacy-fabric-api-")
            || n.Equals("optifine.jar")
            || IsBundledPerfJar(jarName);
    }

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
            // "immediatelyfast-", not "-fabric-": pre-1.20 releases are plain
            // "ImmediatelyFast-1.1.12+1.18.2.jar" — both shapes, one family.
            || n.StartsWith("immediatelyfast-")
            || n.StartsWith("modernfix-fabric-")
            // The extras stack (2026-07-20): Sodium Extra, MoreCulling + its
            // Cloth Config, Cull Leaves + its MidnightLib. Each covers every
            // filename shape the project has shipped ("sodium-extra-fabric-"
            // and "sodium-extra-", "moreculling-fabric-" and "moreculling-",
            // "cullleaves-fabric-" and bare "cullleaves-").
            || n.StartsWith("sodium-extra-")
            || n.StartsWith("moreculling-")
            || n.StartsWith("cloth-config-")
            || n.StartsWith("cullleaves-")
            || n.StartsWith("midnightlib-")
            || IsIrisJar(n);
    }

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
    // NOTE: ModFamilyKey returns the FIRST matching prefix, so no entry here
    // may be a prefix of another (two spellings of one family would split it
    // into two "families" and defeat the one-enabled-copy rule).
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
        // Covers both "ImmediatelyFast-Fabric-*" and the pre-1.20 plain
        // "ImmediatelyFast-*" shape as ONE family.
        "immediatelyfast-",
        "modernfix-fabric-",
        // The extras stack (2026-07-20) — see IsBundledPerfJar.
        "sodium-extra-",
        "moreculling-",
        "cloth-config-",
        "cullleaves-",
        "midnightlib-",
    };

    /// <summary>
    /// Groups a managed jar under its mod-family key so provisioning can keep
    /// exactly ONE enabled copy per family. Fabric refuses to boot at all on a
    /// duplicate mod id, so two coexisting copies of e.g. fabric-api (a player
    /// updating it by hand next to the launcher's, or a catalog version bump
    /// leaving the old pin behind) turn into "the game never launches".
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
