using System.Net.Http;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.Core.Components;

/// <summary>The launch Play refused: whose mods, where they are, and how Origin's own jars are told apart.</summary>
/// <param name="Profile">The mod profile the launch used, or null for the version's shared folder.</param>
/// <param name="ProfileFolder">That profile's mods folder (OriginPaths.ProfileModsDir), or null.</param>
public sealed record FixSource(
    string Version, ModProfile? Profile, string SharedFolder, string? ProfileFolder,
    Func<string, bool> IsManaged, Func<string, bool> IsOriginProvisioned, Func<string, bool>? ConfirmOrigin = null);

/// <summary>
/// What one click of Fix Compatibility would do, in plain words, before anything is touched. The fix is always a
/// COPY (Core/Compat/RepairExecutor.Copy): the profile or folder the player made is never changed.
/// </summary>
/// <param name="Configuration">The Sodium/Iris configuration the copy is planned for (Core/Components).</param>
/// <param name="ComponentPins">The copy's ModProfile.ComponentPins.</param>
/// <param name="PinChanged">True when the copy pins a different configuration than the source did.</param>
public sealed record FixProposal(
    FixSource Source, VettedConfiguration Configuration, string? ComponentPins, bool PinChanged,
    RepairPlan Plan, CompatReport Report, CompatPlatform Platform)
{
    /// <summary>True when the click changes something (otherwise the prompt only explains).</summary>
    public bool CanFix => !Plan.IsEmpty || PinChanged;

    /// <summary>The changes, one plain line each, in the order they happen.</summary>
    public IReadOnlyList<string> Changes
    {
        get
        {
            var lines = new List<string>();
            if (PinChanged)
                lines.Add(Configuration.IsDefault
                    ? "Use Origin's current Sodium and Iris."
                    : $"Use Sodium {VersionOf("sodium")} and Iris {VersionOf("iris")}, an older pair Origin has tested on {Source.Version}.");
            foreach (var step in Plan.Steps)
            {
                lines.Add(step switch
                {
                    AddModStep a => $"Add {a.Offer.Id} {a.Offer.Version} from Modrinth (its checksum is checked).",
                    DisableModStep d => $"Turn off {d.Mod.DisplayName}: {d.Why}",
                    _ => step.Why,
                });
            }
            return lines;
        }
    }

    /// <summary>What this fix cannot mend, one plain line each ("why there is no fix").</summary>
    public IReadOnlyList<string> NoFix
    {
        get
        {
            var lines = new List<string>();
            var seen = new HashSet<ModFile>(ReferenceEqualityComparer.Instance);
            foreach (var u in Plan.Unfixable)
            {
                var mod = u.Finding.Subject;
                if (seen.Add(mod))
                    lines.Add($"{mod.DisplayName}: {Report.ReasonFor(mod) ?? u.Finding.Summary} {u.Reason}");
            }
            if (!CanFix && lines.Count == 0)
                lines.Add("Origin found no change it can make that lets these mods start. The check can be wrong: Launch anyway tries them as they are.");
            return lines;
        }
    }

    private string VersionOf(string modId) =>
        Configuration.Files.FirstOrDefault(f => f.ModId == modId)?.ModVersion ?? "?";
}

/// <summary>
/// Fix Compatibility: plans the smallest repair of a refused launch set and makes it as a new, fixed profile.
///   * Planning compares every vetted configuration of the version (only for a profile, as at Play) and keeps the one
///     that needs the fewest changes, so "use the older Sodium + Iris" is offered only when the spike proved that pair.
///   * A missing dependency may come from Modrinth: only its mod id is sent (ModrinthDependencyCatalog).
///   * Applying it is <see cref="RepairExecutor.ApplyToCopy"/> (journaled, all-or-nothing); the copy is selected.
/// Runs off the UI thread (the caller's Task.Run); nothing here touches the original files except to read them.
/// </summary>
public static class FixCompatibility
{
    public static async Task<FixProposal?> PlanAsync(
        FixSource source, ComponentStore? store, HttpClient? http, CancellationToken ct)
    {
        var platform = LaunchPreflight.PlatformFor(source.Version);
        var configurations = source.Profile != null && store != null
            ? ConfigurationCatalog.For(source.Version)
            : ConfigurationCatalog.For(source.Version).Where(c => c.IsDefault).ToList();

        var reports = new List<(VettedConfiguration Config, CompatReport Report)>();
        foreach (var config in configurations)
        {
            ct.ThrowIfCancellationRequested();
            var swap = config.IsDefault || store == null ? null : ConfigurationLaunch.SwapFor(config, store);
            if (!config.IsDefault && swap == null) continue;
            var report = LaunchPreflight.Inspect(source.Version, source.SharedFolder, source.ProfileFolder, externalMods: true,
                source.IsManaged, source.IsOriginProvisioned, source.ConfirmOrigin, swap, ct);
            if (report != null) reports.Add((config, report));
        }
        if (reports.Count == 0) return null;

        IDependencyCatalog? catalog = null;
        var missing = ModrinthDependencyCatalog.MissingIds(reports.Select(r => r.Report));
        if (http != null && missing.Count > 0)
            catalog = await ModrinthDependencyCatalog.ResolveAsync(http, missing, source.Version, ct).ConfigureAwait(false);

        return Choose(source, reports, catalog, platform);
    }

    /// <summary>The planning half with the reports and catalog in hand (pure; the tests drive it directly).</summary>
    public static FixProposal Choose(
        FixSource source, IReadOnlyList<(VettedConfiguration Config, CompatReport Report)> reports,
        IDependencyCatalog? catalog, CompatPlatform platform)
    {
        var folder = source.ProfileFolder ?? source.SharedFolder;
        var context = new RepairContext(platform, folder, new[] { folder }, Catalog: catalog, AddedRole: ModRole.Profile);
        var sourcePins = source.Profile?.ComponentPins;
        var sourcePair = ComponentPinSet.Parse(sourcePins).GetValueOrDefault(ConfigurationCatalog.PairFamily);

        FixProposal? best = null;
        (int, int, int, int) bestScore = default;
        foreach (var (config, report) in reports)
        {
            var plan = RepairPlanner.Plan(report, context);
            var pins = ComponentPinSet.With(sourcePins, ConfigurationCatalog.PairFamily, config.PairId);
            var changed = !string.Equals(config.PairId, sourcePair, StringComparison.Ordinal);
            // Fewest things left broken, then fewest mods turned off, then fewest downloads, then the newer configuration.
            var score = (plan.Unfixable.Count, plan.Steps.OfType<DisableModStep>().Count(), plan.Steps.OfType<AddModStep>().Count(), config.Rank * -1);
            if (best == null || score.CompareTo(bestScore) < 0)
            {
                best = new FixProposal(source, config, pins, changed, plan, report, platform);
                bestScore = score;
            }
        }
        return best!;
    }

    /// <summary>Builds the fixed copy (and selects it). The caller runs it off the UI thread.</summary>
    public static RepairCopyResult Apply(
        FixProposal proposal, string profilesDir, string journalsDir, Action<Action<LauncherSettings>> update,
        IDependencyFetcher? fetcher, Func<string, bool> isRegistered, Func<string, string> profilesDirFor)
    {
        // A fix that was interrupted before (power cut, crash) is finished or cleared first: one at a time.
        RepairExecutor.RecoverCopies(journalsDir, profilesDirFor, isRegistered);

        var source = proposal.Source;
        var baseName = source.Profile?.Name ?? "My mods";
        const string suffix = " fixed";
        if (baseName.Length + suffix.Length > ProfileRules.MaxNameLength)
            baseName = baseName[..(ProfileRules.MaxNameLength - suffix.Length)].TrimEnd();
        var copy = new ModProfile
        {
            Id = ProfileRules.NewId(),
            Name = baseName + suffix,
            Version = source.Version,
            ServerName = source.Profile?.ServerName,
            ServerAddress = source.Profile?.ServerAddress,
            Tint = source.Profile?.Tint,
            Icon = source.Profile?.Icon,
            ComponentPins = proposal.ComponentPins,
        };
        var onlyFiles = source.ProfileFolder == null
            ? proposal.Report.Entries.Where(e => e.Role == ModRole.SharedLoose).Select(e => e.FileName)
                .ToHashSet(StringComparer.OrdinalIgnoreCase)
            : null;
        var notes = new List<string>();
        if (proposal.PinChanged) notes.Add(proposal.Changes[0]);
        notes.AddRange(proposal.Plan.Unfixable.Count > 0 ? proposal.NoFix.Select(l => "Not fixed: " + l) : Array.Empty<string>());

        var request = new RepairCopyRequest(source.Version, copy, source.ProfileFolder ?? source.SharedFolder, onlyFiles,
            proposal.Plan, proposal.Platform, notes);
        return RepairExecutor.ApplyToCopy(request, profilesDir, journalsDir, update, fetcher);
    }
}
