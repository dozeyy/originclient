using System.IO;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.Core.Import;

// Step 4 of the guided import (Will's decision 04, 2026-10-25): after a run, re-read what landed and sort every item
// into one of four answers, each with its reason:
//   Succeeded   - it is on disk where the import put it (checked again, not taken from the plan)
//   Skipped     - left out on purpose (Origin already has it, built for another Minecraft, a second copy)
//   Unsupported - Origin can't bring this kind of thing over (accounts, Forge/Quilt jars, launchers it can't read)
//   Failed      - it should have come over and didn't (unreadable, gone at apply, missing when checked again)
// Every profile the run made or reused is checked like a launch would check it (CompatEngine.Check over Origin's own
// turned-on jars plus the profile's), so a setup that can't start says so on the report, not at Play.
// Pure apart from the file reads it is handed; never writes.

public enum VerifyBucket { Succeeded, Skipped, Unsupported, Failed }

/// <param name="Setup">"Prism Launcher: Fabulously Optimized", or "" for a line about the whole import.</param>
public sealed record VerifyItem(VerifyBucket Bucket, string Setup, string Name, string Reason);

/// <summary>One imported setup's profile, checked as a launch would check it.</summary>
/// <param name="Problems">"Sodium Extra: needs Sodium 0.6 or newer" per mod that stops the game; empty = it can start.</param>
public sealed record ProfileCheck(
    string Version, string ProfileId, string ProfileName, string ModsDir, int Mods, bool Reused, IReadOnlyList<string> Problems)
{
    public bool CanStart => Problems.Count == 0;

    /// <summary>What the report says under a profile that can't start (the brief's wording, decision 04).</summary>
    public string? FixLine => CanStart ? null : ImportVerifier.FixCompatibilityLine;
}

public sealed class ImportVerification
{
    public IReadOnlyList<VerifyItem> Items { get; init; } = Array.Empty<VerifyItem>();
    public IReadOnlyList<ProfileCheck> Profiles { get; init; } = Array.Empty<ProfileCheck>();

    public IEnumerable<VerifyItem> In(VerifyBucket bucket) => Items.Where(i => i.Bucket == bucket);
    public int Count(VerifyBucket bucket) => Items.Count(i => i.Bucket == bucket);
}

public static class ImportVerifier
{
    public const string FixCompatibilityLine = "Can't start as it is. A repaired copy can be made with Fix Compatibility.";

    /// <summary>
    /// What Origin never imports, named every time so nothing is implied: the report's standing "Unsupported" lines.
    /// </summary>
    public static readonly IReadOnlyList<(string Name, string Reason)> NeverImported = new[]
    {
        ("Accounts and sign-ins", "Never copied from another launcher. Sign in again in Origin."),
        ("Launcher app data", "Other launchers' own databases, cosmetics and friends lists stay with them."),
        ("ATLauncher, GDLauncher and Technic", "Origin can't read these launchers' setups yet."),
        ("Modpack files (.mrpack, CurseForge zips)", "Pack files aren't opened. Import the setup the launcher made from the pack."),
        ("Quilt mods", "They need the Quilt loader. Origin runs Fabric, so they stay behind."),
    };

    /// <summary>The profile check a report runs: (version, profile mods folder) to the mods that stop the game.</summary>
    public delegate IReadOnlyList<string> ProfileChecker(string version, string profileModsDir);

    /// <summary>File or folder exists: the real re-read. Tests hand in their own.</summary>
    public static bool OnDisk(string path) => File.Exists(path) || Directory.Exists(path);

    public static ImportVerification Verify(BulkImportPlan plan, BulkImportResult result, Func<string, bool>? exists = null,
        ProfileChecker? checkProfile = null)
    {
        exists ??= OnDisk;
        var items = new List<VerifyItem>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        void Add(VerifyBucket bucket, string setup, string name, string reason)
        {
            if (seen.Add(setup + "\u0001" + name)) items.Add(new VerifyItem(bucket, setup, name, reason));
        }

        var planned = plan.AllSetups.GroupBy(s => s.Setup.BulkId(), StringComparer.Ordinal)
            .ToDictionary(g => g.Key, g => g.First(), StringComparer.Ordinal);
        var profiles = new List<ProfileCheck>();

        if (result.Error != null && result.Versions.Count == 0)
            Add(VerifyBucket.Failed, "", "This import", "Nothing was changed: " + result.Error);

        foreach (var version in result.Versions)
        {
            foreach (var outcome in version.Setups)
            {
                var label = outcome.Setup.BulkLabel();
                planned.TryGetValue(outcome.Setup.BulkId(), out var ps);
                switch (outcome.Kind)
                {
                    case SetupResultKind.Failed:
                        Add(VerifyBucket.Failed, label, "Whole setup", outcome.Message ?? outcome.Result?.Error ?? "It stopped part way and was put back.");
                        continue;
                    case SetupResultKind.Skipped:
                        Add(VerifyBucket.Skipped, label, "Whole setup", outcome.Message ?? "It wasn't imported this time.");
                        continue;
                }
                if (ps == null) continue;
                var p = ps.Plan;

                // Credential-looking files: never read, never copied. They are accounts, so "Unsupported", by name.
                foreach (var name in p.SkippedForSafety)
                    Add(VerifyBucket.Unsupported, label, name, "Looks like a sign-in or account file. Accounts never come over.");

                // Left out while it ran (an antivirus held it, it was deleted since the plan): it should have come.
                var leftOut = outcome.LeftOut ?? outcome.Result?.LeftOut ?? Array.Empty<SummaryLine>();
                foreach (var line in leftOut)
                    Add(VerifyBucket.Failed, label, line.Name, line.Reason ?? "It couldn't be read while importing.");

                // Mods the plan didn't copy, each with the engine's own reason.
                foreach (var mod in p.Mods.Where(m => !m.IsCopied))
                    Add(BucketOf(mod.Outcome), label, mod.DisplayName ?? mod.FileName, mod.Reason ?? DefaultReason(mod.Outcome));

                // Other things the plan said won't come over (packs, worlds, settings) keep their reasons.
                foreach (var line in (outcome.Lines ?? p.Lines).Where(l => l.Section == SummarySection.NotImported && l.Kind != PlanItemKind.Mod))
                    Add(VerifyBucket.Skipped, label, line.Name, line.Reason ?? "Left out.");

                // The re-read: every copy the plan made must be on disk now. Counted per kind for the Succeeded list.
                var leftNames = new HashSet<string>(leftOut.Select(l => l.Name), StringComparer.OrdinalIgnoreCase);
                var landed = new Dictionary<PlanItemKind, int>();
                foreach (var op in p.Ops)
                {
                    if (op.Fallback || op.Kind == OpKind.Custom || leftNames.Contains(op.Label)) continue;
                    if (exists(op.Dest)) landed[op.Item] = landed.GetValueOrDefault(op.Item) + 1;
                    else Add(VerifyBucket.Failed, label, op.Label, "It wasn't there when Origin checked after importing.");
                }
                foreach (var (kind, n) in landed.OrderBy(k => k.Key))
                    Add(VerifyBucket.Succeeded, label, Count(kind, n), WhereItLanded(kind, outcome, version.Version));
                if (outcome.ProfileReused && outcome.ProfileName != null)
                    Add(VerifyBucket.Succeeded, label, "Mods", $"Already in the profile {outcome.ProfileName} from an earlier import.");

                if (outcome.ProfileId != null && outcome.ProfileModsDir != null && profiles.All(x => x.ProfileId != outcome.ProfileId))
                {
                    var problems = checkProfile?.Invoke(version.Version, outcome.ProfileModsDir) ?? Array.Empty<string>();
                    var jars = p.Mods.Count(m => m.IsCopied);
                    profiles.Add(new ProfileCheck(version.Version, outcome.ProfileId, outcome.ProfileName ?? outcome.ProfileId,
                        outcome.ProfileModsDir, jars, outcome.ProfileReused, problems));
                }
            }
        }

        // Whole setups the run never tried (another launcher's are left off before this: ImportFlowModel.ForDone).
        foreach (var skipped in result.Skipped.Where(s => s.Kind != SkipKind.NotSelected))
        {
            var bucket = skipped.Kind switch
            {
                SkipKind.Unsupported or SkipKind.UnknownVersion => VerifyBucket.Unsupported,
                SkipKind.FolderMissing => VerifyBucket.Failed,
                _ => VerifyBucket.Skipped,
            };
            Add(bucket, skipped.Setup.BulkLabel(), "Whole setup", skipped.Reason);
        }

        foreach (var (name, reason) in NeverImported) Add(VerifyBucket.Unsupported, "", name, reason);
        return new ImportVerification { Items = items, Profiles = profiles };
    }

    private static VerifyBucket BucketOf(ModOutcome outcome) => outcome switch
    {
        ModOutcome.WrongLoader => VerifyBucket.Unsupported,
        ModOutcome.Unreadable => VerifyBucket.Failed,
        _ => VerifyBucket.Skipped, // BuiltIn, WrongMinecraft, Duplicate
    };

    private static string DefaultReason(ModOutcome outcome) => outcome switch
    {
        ModOutcome.WrongLoader => "Made for another mod loader (Forge, NeoForge or Quilt). Origin runs Fabric.",
        ModOutcome.Unreadable => "Not a readable mod file.",
        ModOutcome.BuiltIn => "Origin already includes it.",
        ModOutcome.WrongMinecraft => "Built for another Minecraft version.",
        ModOutcome.Duplicate => "Another copy of the same mod comes over instead.",
        _ => "Left out.",
    };

    private static string Count(PlanItemKind kind, int n)
    {
        var (one, many) = kind switch
        {
            PlanItemKind.Mod => ("mod", "mods"),
            PlanItemKind.Setting => ("settings file", "settings files"),
            PlanItemKind.Config => ("mod settings file", "mod settings files"),
            PlanItemKind.ResourcePack => ("resource pack", "resource packs"),
            PlanItemKind.ShaderPack => ("shader pack", "shader packs"),
            PlanItemKind.World => ("world", "worlds"),
            PlanItemKind.GameFile => ("game file", "game files"),
            PlanItemKind.Cache => ("map cache", "map caches"),
            PlanItemKind.Screenshot => ("screenshot", "screenshots"),
            _ => ("item", "items"),
        };
        return $"{n} {(n == 1 ? one : many)}";
    }

    private static string WhereItLanded(PlanItemKind kind, SetupOutcome outcome, string version) =>
        kind == PlanItemKind.Mod && outcome.ProfileName != null
            ? $"In the profile {outcome.ProfileName} (Minecraft {version})."
            : $"In Origin's Minecraft {version}.";
}

/// <summary>
/// One profile checked the way a launch checks it (LaunchModFolder's launch set): Origin's own turned-on jars from the
/// version's shared mods folder, plus the profile's turned-on jars minus any with an Origin jar's name. Read-only.
/// Calls CompatEngine.Check (existing API only); "stops the game" is LaunchPreflight's rule, restated here because its
/// IsHard is private to that file (keep the two in step).
/// </summary>
public static class ImportProfileCheck
{
    private static readonly HashSet<FindingKind> Stops = new()
    {
        FindingKind.WrongMinecraft, FindingKind.MissingDependency, FindingKind.WrongVersion,
        FindingKind.Breaks, FindingKind.DuplicateId,
    };

    public static IReadOnlyList<string> Check(string version, string sharedModsDir, string profileModsDir,
        Func<string, bool> isOriginProvisioned, CancellationToken ct = default)
    {
        var entries = new List<ModFile>();
        void Add(string dir, ModRole role, bool origin)
        {
            if (!Directory.Exists(dir)) return;
            foreach (var path in Directory.EnumerateFiles(dir, "*.jar"))
            {
                ct.ThrowIfCancellationRequested();
                var name = Path.GetFileName(path);
                if (isOriginProvisioned(name) != origin) continue;
                var info = ModFolderScanner.ReadInfo(path) ?? new ModInfo { FileName = name, ReadFailed = true };
                entries.Add(new ModFile(path, name, Enabled: true, role, info));
            }
        }
        Add(sharedModsDir, ModRole.Managed, origin: true);
        Add(profileModsDir, ModRole.Profile, origin: false);
        if (entries.All(e => e.Role == ModRole.Managed)) return Array.Empty<string>();

        var platform = LaunchPreflight.PlatformFor(version);
        if (platform.IsLegacyForge) return Array.Empty<string>(); // mcmod.info is advisory: never a stop
        var report = CompatEngine.Check(entries, platform);
        var problems = new List<string>();
        var named = new HashSet<ModFile>(ReferenceEqualityComparer.Instance);
        foreach (var f in report.Findings)
        {
            if (!StopsGame(f)) continue;
            var subject = f.Subject.Role == ModRole.Managed ? f.Related! : f.Subject;
            if (!named.Add(subject)) continue;
            var reason = f.Kind is FindingKind.MissingDependency or FindingKind.WrongVersion && report.ReasonFor(f.Subject) is { } r ? r : f.Summary;
            problems.Add($"{subject.DisplayName}: {reason}");
        }
        return problems;
    }

    private static bool StopsGame(CompatFinding f)
    {
        if (f.Severity != Severity.Blocking || f.Certainty != Certainty.Confirmed) return false;
        if (!Stops.Contains(f.Kind) || f.IsKnockOn) return false;
        if (f.Subject.Role != ModRole.Managed) return true;
        return f.Kind == FindingKind.Breaks && f.Related is { Role: not ModRole.Managed };
    }
}
