namespace OriginLauncher.App.Core.Components;

public enum PickOutcome
{
    /// <summary>A configuration with no blocking issue was found.</summary>
    Picked,
    /// <summary>Every candidate has a blocking issue: the top candidate is returned and the pre-launch check prompts.</summary>
    NoneClean,
    /// <summary>A game of this profile is running with a different configuration: switching now is refused.</summary>
    Refused,
}

/// <param name="Configuration">What to launch (for <see cref="PickOutcome.Refused"/>, what would have been picked).</param>
/// <param name="Log">Plain lines saying why, for the launch log.</param>
public sealed record PickResult(PickOutcome Outcome, VettedConfiguration Configuration, IReadOnlyList<string> Log);

/// <summary>
/// Picks the configuration a profile launches with, at Play, with no UI: the newest vetted configuration that has no
/// blocking compatibility issue. Rules:
///   * a pin ("sodium-iris=0.6.5+1.8.1") narrows the candidates to that pair; a pin for a pair not vetted on this
///     version is ignored (and logged), never trusted;
///   * candidates are tried newest first, so the shipped set is checked first and alternates cost nothing while it
///     starts; <paramref name="hasBlocking"/> is only called for the ranks it has to look at;
///   * between equally new clean candidates, one already in the store wins (no download);
///   * none clean: the top candidate, so the pre-launch check shows its issues (and Fix Compatibility);
///   * a running game of the profile on another configuration: refused (its files are in use).
/// Pure: the caller supplies the check, the store test and what is running.
/// </summary>
public static class ConfigurationSelector
{
    public static PickResult Pick(
        IReadOnlyList<VettedConfiguration> vetted,
        IReadOnlyDictionary<string, string> pins,
        Func<VettedConfiguration, bool> hasBlocking,
        Func<VettedConfiguration, bool> isMaterialised,
        IReadOnlyCollection<string> running)
    {
        if (vetted.Count == 0) throw new ArgumentException("No configurations.", nameof(vetted));
        var log = new List<string>();
        var candidates = vetted.OrderByDescending(c => c.Rank).ToList();

        if (pins.TryGetValue(ConfigurationCatalog.PairFamily, out var pair))
        {
            var pinned = candidates.Where(c => c.PairId == pair).ToList();
            if (pinned.Count > 0)
            {
                candidates = pinned;
                log.Add($"Pinned to Sodium + Iris {pair}.");
            }
            else
                log.Add($"Pin {ConfigurationCatalog.PairFamily}={pair} is not vetted on {vetted[0].McVersion}; ignored.");
        }

        VettedConfiguration? chosen = null;
        foreach (var group in candidates.GroupBy(c => c.Rank)) // already newest first
        {
            var clean = new List<VettedConfiguration>();
            foreach (var c in group)
            {
                if (hasBlocking(c)) log.Add($"{c.Id}: has a blocking issue.");
                else clean.Add(c);
            }
            if (clean.Count == 0) continue;
            chosen = clean.FirstOrDefault(isMaterialised) ?? clean[0];
            break;
        }

        var outcome = PickOutcome.Picked;
        if (chosen == null)
        {
            chosen = candidates[0];
            outcome = PickOutcome.NoneClean;
            log.Add($"No configuration starts cleanly; checking {chosen.Id} with the player.");
        }
        else
            log.Add($"Picked {chosen.Id} ({chosen.Evidence}).");

        var other = running.FirstOrDefault(r => r != chosen.Id);
        if (other != null)
        {
            log.Add($"A game of this profile is running with {other}; not switching to {chosen.Id}.");
            return new PickResult(PickOutcome.Refused, chosen, log);
        }
        return new PickResult(outcome, chosen, log);
    }
}

/// <summary>
/// Which configurations each profile's running games use (launcher memory only). Home registers a game when it
/// starts and releases it when it exits; the selector reads it so Play never swaps the files of a running game.
/// </summary>
public static class RunningConfigurations
{
    private static readonly Dictionary<string, Dictionary<string, int>> Running = new(StringComparer.Ordinal);

    public static IReadOnlyCollection<string> For(string profileId)
    {
        lock (Running)
            return Running.TryGetValue(profileId, out var ids) ? ids.Keys.ToList() : Array.Empty<string>();
    }

    /// <summary>Registers one game; dispose the result when it exits.</summary>
    public static IDisposable Started(string profileId, string configurationId)
    {
        lock (Running)
        {
            if (!Running.TryGetValue(profileId, out var ids)) Running[profileId] = ids = new(StringComparer.Ordinal);
            ids[configurationId] = ids.GetValueOrDefault(configurationId) + 1;
        }
        return new Release(profileId, configurationId);
    }

    private sealed class Release(string profileId, string configurationId) : IDisposable
    {
        private int _done;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _done, 1) == 1) return;
            lock (Running)
            {
                if (!Running.TryGetValue(profileId, out var ids) || !ids.TryGetValue(configurationId, out var n)) return;
                if (n <= 1) ids.Remove(configurationId);
                else ids[configurationId] = n - 1;
                if (ids.Count == 0) Running.Remove(profileId);
            }
        }
    }
}
