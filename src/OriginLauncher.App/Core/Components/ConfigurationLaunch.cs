using OriginLauncher.App.Core.Compat;

namespace OriginLauncher.App.Core.Components;

/// <summary>A game of the same profile runs on another configuration; Play waits until it is closed.</summary>
public sealed class ConfigurationInUseException : Exception
{
    public ConfigurationInUseException()
        : base("A game using this profile is still running with a different setup. Close it first, then press Play.") { }
}

/// <summary>The configuration a profile launch uses.</summary>
/// <param name="Swap">Null for the shipped set; otherwise the store files and what they replace.</param>
public sealed record ConfigurationChoice(VettedConfiguration Configuration, LaunchSwap? Swap);

/// <summary>
/// Play on a profile whose version has vetted alternates (ConfigurationCatalog): pick, fetch, check.
///   1. ConfigurationSelector picks (the shipped set is checked first; alternates only when it would not start);
///   2. a picked alternate's files are brought into the store; if that fails, the shipped set is used instead;
///   3. the pre-launch check runs on exactly what will load: a blocking issue throws IncompatibleModsException
///      (Home's Fix Compatibility prompt), unless the player already chose "Launch anyway" for that exact set.
/// The checks are passed in, so this stays free of the mods folder and testable.
/// </summary>
public static class ConfigurationLaunch
{
    /// <summary>What <paramref name="configuration"/> changes in the launch set; null for the shipped set.</summary>
    public static LaunchSwap? SwapFor(VettedConfiguration configuration, ComponentStore store)
    {
        if (configuration.IsDefault) return null;
        var files = new List<SwapFile>();
        foreach (var f in configuration.Files)
        {
            if (store.PathFor(f.Family, f.Pin) is not { } path) return null;
            files.Add(new SwapFile(path, new ModInfo
            {
                FileName = f.Pin.FileName,
                Loader = ModLoader.Fabric,
                Id = f.ModId,
                Version = f.ModVersion,
                Name = f.ModId == "iris" ? "Iris" : f.ModId == "sodium" ? "Sodium" : f.ModId,
            }));
        }
        return new LaunchSwap(configuration.Id, ConfigurationCatalog.ReplacedPrefixes, ConfigurationCatalog.ReplacedIds, files);
    }

    /// <param name="evaluate">The check without the throw (LaunchPreflight.Evaluate on this launch set).</param>
    /// <param name="run">The check that throws for the prompt (LaunchPreflight.Run with the player's override key).</param>
    /// <param name="running">Configurations this profile's running games use (RunningConfigurations.For).</param>
    /// <param name="log">Receives the plain lines for the launch log.</param>
    public static async Task<ConfigurationChoice> ChooseAsync(
        string version, string? componentPins, ComponentStore store,
        Func<LaunchSwap?, LaunchVerdict> evaluate, Action<LaunchSwap?> run,
        IReadOnlyCollection<string> running, Action<string> log, CancellationToken ct)
    {
        var vetted = ConfigurationCatalog.For(version);
        var pins = ComponentPinSet.Parse(componentPins);
        var pick = ConfigurationSelector.Pick(vetted, pins,
            c => evaluate(SwapFor(c, store)).Blocking.Count > 0,
            c => c.Files.All(f => store.IsMaterialised(f.Family, f.Pin)),
            running);
        foreach (var line in pick.Log) log(line);
        if (pick.Outcome == PickOutcome.Refused) throw new ConfigurationInUseException();

        var chosen = pick.Configuration;
        if (!chosen.IsDefault)
        {
            foreach (var f in chosen.Files)
            {
                if (await store.EnsureAsync(f.Family, f.Pin, ct).ConfigureAwait(false) != null) continue;
                log($"{f.Pin.FileName} could not be fetched; launching the shipped set instead.");
                chosen = vetted.First(c => c.IsDefault);
                if (running.Any(r => r != chosen.Id)) throw new ConfigurationInUseException();
                break;
            }
        }

        var swap = SwapFor(chosen, store);
        run(swap); // the real files now: throws for the prompt when they would not start
        log($"Launching with {chosen.Id}.");
        return new ConfigurationChoice(chosen, swap);
    }
}
