using System.IO;

namespace OriginLauncher.App.Core.Compat;

public enum CrashRepairOutcome
{
    /// <summary>The blamed mod was turned off (foo.jar -> foo.jar.disabled).</summary>
    Disabled,

    /// <summary>The jar isn't in any folder that game loads mods from (a profile game does not load the shared loose mods).</summary>
    NotFound,

    /// <summary>It is one of Origin's own mods: never turned off from a crash screen.</summary>
    Protected,

    /// <summary>It was found but couldn't be renamed (the turned-off name is taken, or another reason).</summary>
    Failed,

    /// <summary>It couldn't be renamed because a running game holds it: another account playing the same version
    /// (the mods folder is shared per version).</summary>
    InUse,
}

public sealed record CrashRepairResult(CrashRepairOutcome Outcome, string? Folder, string? Message);

/// <summary>
/// The crash screen's "turn that mod off" fix, as a repair step: the blamed jar is looked up in the folder THIS game
/// loads it from, then turned off through <see cref="RepairExecutor"/> (rename only, never an overwrite).
/// Which folder: a game launched with a mod profile loads the profile's own mods plus Origin's provisioned jars, so a
/// blamed player mod can only be in the profile folder, and the shared folder is never touched for it (that is what
/// turning off "the version's" mod would silently do to every other profile and account). A game with no profile
/// loads the shared folder.
/// </summary>
public static class CrashRepair
{
    /// <param name="version">The Minecraft version of the crashed game.</param>
    /// <param name="profileId">The mod profile that game was launched with, or null for the shared folder.</param>
    /// <param name="blamedJar">The jar file name the crash report blamed ("foo.jar").</param>
    /// <param name="isOriginsOwnFile">Origin's own built-in jar, decided by the FILE (<c>ModManager.IsManagedFile</c>:
    /// Origin's name shape and Origin's bytes); never turned off. Decided by name before 2026-11-03, which refused a
    /// player's own Sodium/Iris/Fabric API (theirs since 2026-10-25) and Simple Voice Chat (a normal switch on the
    /// Mods page), leaving only "try without my mods".</param>
    public static CrashRepairResult DisableBlamed(
        string version, string? profileId, string blamedJar, Func<string, bool> isOriginsOwnFile) =>
        DisableBlamed(
            profileId == null ? OriginPaths.ModsDir(version) : OriginPaths.ProfileModsDir(version, profileId),
            blamedJar, isOriginsOwnFile);

    /// <summary>The same with the folder the game loads the mod from already chosen.</summary>
    public static CrashRepairResult DisableBlamed(string folder, string blamedJar, Func<string, bool> isOriginsOwnFile)
    {
        var name = Path.GetFileName(blamedJar); // the report names a file, never a path
        if (name.Length == 0 || !name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase))
            return new CrashRepairResult(CrashRepairOutcome.NotFound, folder, "The crash report didn't name a mod file.");

        var path = Path.Combine(folder, name);
        if (!File.Exists(path))
            return new CrashRepairResult(CrashRepairOutcome.NotFound, folder, "That mod isn't in the folder this game loads mods from.");
        if (isOriginsOwnFile(path))
            return new CrashRepairResult(CrashRepairOutcome.Protected, folder, "That is one of Origin's own mods, so it stays as it is.");

        var info = ModFolderScanner.ReadInfo(path, readNested: false) ?? new ModInfo { FileName = name, ReadFailed = true };
        var mod = new ModFile(path, name, Enabled: true, ModRole.SharedLoose, info);
        var finding = new CompatFinding(FindingKind.CrashBlamed, Severity.Blocking, Certainty.Likely, mod,
            "The crash report blamed this mod.") { Excludes = true };
        var plan = new RepairPlan(
            new RepairStep[] { new DisableModStep(mod, finding.Summary, Certainty.Likely) }, Array.Empty<UnfixableFinding>());
        var context = new RepairContext(
            new CompatPlatform("", LoaderFamily.Fabric, null, ""), folder, new[] { folder },
            IsProtected: m => isOriginsOwnFile(m.Path));

        var applied = RepairExecutor.Apply(plan, context).Steps[0];
        if (applied.Ok) return new CrashRepairResult(CrashRepairOutcome.Disabled, folder, null);
        return new CrashRepairResult(
            applied.Error == RepairExecutor.InUseMessage ? CrashRepairOutcome.InUse : CrashRepairOutcome.Failed,
            folder, applied.Error);
    }

    /// <summary>
    /// The Home status line when a crash fix couldn't turn every blamed mod off. A jar another account's running game
    /// holds can't be changed from the Mods tab either, so that case names the real way out: close that game.
    /// </summary>
    public static string FailureLine(string version, IReadOnlyList<(string Jar, CrashRepairOutcome Outcome)> failed)
    {
        var jars = string.Join(", ", failed.Select(f => f.Jar));
        return failed.Any(f => f.Outcome == CrashRepairOutcome.InUse)
            ? $"Couldn't turn off {jars}: a Minecraft {version} game on another account is using it. Close that game, then press Play."
            : $"Couldn't turn off {jars}. Turn it off on the Mods tab, then press Play.";
    }
}
