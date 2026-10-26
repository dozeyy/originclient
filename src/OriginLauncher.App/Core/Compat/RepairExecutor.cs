using System.IO;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// The ONLY place a repair touches disk. The rules are the Origin constraints on the shared mods folder:
///   * it never deletes a file: a mod is turned off by renaming foo.jar to foo.jar.disabled;
///   * it never overwrites: a step whose target name is taken fails and says so;
///   * it never rewrites a jar in place (another account's running game may hold it): a new file is fetched to a
///     .part name, checked, then moved to a name that did not exist before;
///   * it only changes direct children of the folders the context allows (every one comes from OriginPaths).
/// A step that can't be done (the game holds the file, the name is taken) is reported and the rest still run: one
/// stuck mod must not stop the others being fixed. Never throws for an I/O problem.
/// </summary>
public static partial class RepairExecutor
{
    public static RepairResult Apply(RepairPlan plan, RepairContext context, IDependencyFetcher? fetcher = null)
    {
        var applied = new List<AppliedStep>();
        foreach (var step in plan.Steps)
        {
            applied.Add(step switch
            {
                DisableModStep disable => Disable(disable, context),
                AddModStep add => Add(add, context, fetcher),
                _ => new AppliedStep(step, false, "Unknown repair step.", null, null),
            });
        }
        return new RepairResult(applied);
    }

    /// <summary>
    /// Puts back what <paramref name="result"/> did: turned-off mods are renamed back. A file that was ADDED is not
    /// deleted (nothing here deletes); it is turned off, so the set is exactly as it was for the game.
    /// </summary>
    public static RepairResult Undo(RepairResult result, RepairContext context)
    {
        var undone = new List<AppliedStep>();
        foreach (var done in result.Done.Reverse())
        {
            switch (done.Step)
            {
                case DisableModStep disable:
                    undone.Add(Rename(disable, done.To!, done.From!, context));
                    break;
                case AddModStep add:
                    undone.Add(Rename(add, done.To!, done.To! + ".disabled", context));
                    break;
            }
        }
        return new RepairResult(undone);
    }

    private static AppliedStep Disable(DisableModStep step, RepairContext context)
    {
        var mod = step.Mod;
        if (mod.Role == ModRole.Managed || context.IsProtected?.Invoke(mod) == true)
            return Fail(step, "That mod is protected, so it stays as it is.");
        if (!mod.Path.EndsWith(".jar", StringComparison.OrdinalIgnoreCase))
            return Fail(step, "Only a turned-on .jar file can be turned off.");
        return Rename(step, step.From, step.To, context);
    }

    // File.Move without overwrite: it throws when the target exists, which is reported, never forced.
    private static AppliedStep Rename(RepairStep step, string from, string to, RepairContext context)
    {
        if (!InAllowedFolder(from, context) || !InAllowedFolder(to, context))
            return Fail(step, "That file is outside the mods folders this repair may change.");
        try
        {
            if (!File.Exists(from)) return Fail(step, "That mod is no longer in the mods folder.");
            if (File.Exists(to)) return Fail(step, "A file with the turned-off name is already there, so nothing was changed.");
            File.Move(from, to);
            return new AppliedStep(step, true, null, from, to);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return Fail(step, "Couldn't change that mod - close Minecraft (on every account playing this version) and try again.");
        }
    }

    private static AppliedStep Add(AddModStep step, RepairContext context, IDependencyFetcher? fetcher)
    {
        var dest = step.DestinationPath;
        var offer = step.Offer;
        if (fetcher == null) return Fail(step, "There is no way to get that mod right now.");
        if (!dest.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || !InAllowedFolder(dest, context))
            return Fail(step, "That file would land outside the mods folders this repair may change.");

        var part = dest + ".part";
        try
        {
            if (File.Exists(dest) || File.Exists(dest + ".disabled"))
                return Fail(step, "A mod with that file name is already there, so nothing was added.");
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);

            if (!fetcher.Fetch(offer, part, out var error) || !File.Exists(part))
            {
                DeleteOwnPart(part);
                return Fail(step, error ?? "Couldn't get that mod.");
            }
            if ((offer.Sha256 != null && !HashMatches(part, offer.Sha256))
                || (offer.Sha512 != null && !new Loaders.PerfMod("", "", "", offer.Sha512).Matches(part)))
            {
                DeleteOwnPart(part);
                return Fail(step, "The downloaded file isn't the one expected, so it was not added.");
            }
            using (var check = new FileStream(part, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                var info = ModMetadataReader.Read(check, Path.GetFileName(dest), readNested: false);
                if (info.ReadFailed || info.Id == null || (info.Id != offer.Id && !info.Provides.Contains(offer.Id)))
                {
                    check.Dispose();
                    DeleteOwnPart(part);
                    return Fail(step, "The file isn't the mod that was expected, so it was not added.");
                }
            }
            File.Move(part, dest); // no overwrite: it throws if a mod appeared under that name meanwhile
            return new AppliedStep(step, true, null, null, dest);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidDataException)
        {
            DeleteOwnPart(part);
            return Fail(step, "Couldn't add that mod - " + ex.Message);
        }
    }

    // The only deletion in repairs: the temporary .part file this very call created.
    private static void DeleteOwnPart(string part)
    {
        try { if (part.EndsWith(".part", StringComparison.Ordinal) && File.Exists(part)) File.Delete(part); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* a stray .part is harmless: the game ignores it */ }
    }

    private static bool HashMatches(string path, string sha256)
    {
        using var stream = File.OpenRead(path);
        return string.Equals(Convert.ToHexString(SHA256.HashData(stream)), sha256, StringComparison.OrdinalIgnoreCase);
    }

    private static bool InAllowedFolder(string path, RepairContext context)
    {
        string dir;
        try { dir = Path.GetFullPath(Path.GetDirectoryName(path) ?? ""); }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException) { return false; }
        return context.AllowedFolders.Any(f => string.Equals(
            dir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
            Path.GetFullPath(f).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal));
    }

    private static AppliedStep Fail(RepairStep step, string error) => new(step, false, error, null, null);
}
