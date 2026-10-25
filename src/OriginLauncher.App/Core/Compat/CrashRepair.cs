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

    /// <summary>It was found but couldn't be renamed (the game holds it, the turned-off name is taken).</summary>
    Failed,
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
    /// <param name="isOriginProvisioned">Origin's own jar names (<c>ModManager.IsOriginProvisioned</c>); never turned off.</param>
    public static CrashRepairResult DisableBlamed(
        string version, string? profileId, string blamedJar, Func<string, bool> isOriginProvisioned) =>
        DisableBlamed(
            profileId == null ? OriginPaths.ModsDir(version) : OriginPaths.ProfileModsDir(version, profileId),
            blamedJar, isOriginProvisioned);

    /// <summary>The same with the folder the game loads the mod from already chosen.</summary>
    public static CrashRepairResult DisableBlamed(string folder, string blamedJar, Func<string, bool> isOriginProvisioned)
    {
        var name = Path.GetFileName(blamedJar); // the report names a file, never a path
        if (name.Length == 0 || !name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase))
            return new CrashRepairResult(CrashRepairOutcome.NotFound, folder, "The crash report didn't name a mod file.");
        if (isOriginProvisioned(name))
            return new CrashRepairResult(CrashRepairOutcome.Protected, folder, "That is one of Origin's own mods, so it stays as it is.");

        var path = Path.Combine(folder, name);
        if (!File.Exists(path))
            return new CrashRepairResult(CrashRepairOutcome.NotFound, folder, "That mod isn't in the folder this game loads mods from.");

        var info = ModFolderScanner.ReadInfo(path, readNested: false) ?? new ModInfo { FileName = name, ReadFailed = true };
        var mod = new ModFile(path, name, Enabled: true, ModRole.SharedLoose, info);
        var finding = new CompatFinding(FindingKind.CrashBlamed, Severity.Blocking, Certainty.Likely, mod,
            "The crash report blamed this mod.") { Excludes = true };
        var plan = new RepairPlan(
            new RepairStep[] { new DisableModStep(mod, finding.Summary, Certainty.Likely) }, Array.Empty<UnfixableFinding>());
        var context = new RepairContext(
            new CompatPlatform("", LoaderFamily.Fabric, null, ""), folder, new[] { folder },
            IsProtected: m => isOriginProvisioned(m.FileName));

        var applied = RepairExecutor.Apply(plan, context).Steps[0];
        return applied.Ok
            ? new CrashRepairResult(CrashRepairOutcome.Disabled, folder, null)
            : new CrashRepairResult(CrashRepairOutcome.Failed, folder, applied.Error);
    }
}
