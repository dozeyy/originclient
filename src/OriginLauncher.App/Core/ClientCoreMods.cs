namespace OriginLauncher.App.Core;

/// <summary>
/// The core jars of other Minecraft clients (Feather, Lunar, Badlion, LabyMod). They look like Fabric mods but only
/// run inside their own launcher: they need libraries only that launcher ships (Feather's jar mixes into every class
/// and dies at its first one with <c>NoClassDefFoundError: feather/lib/sentry/Sentry</c>, 2026-10-20, blamed on
/// ModernFix because that was the first class transformed). So:
/// <list type="bullet">
/// <item>Import never brings them in (<see cref="Import.Mods.ModSetResolver"/>).</item>
/// <item>A crash whose stack runs through their code blames THEM, not the mod Fabric happened to be loading
/// (<see cref="Launch.CrashAnalyzer"/>), so "Relaunch without ..." turns off the real culprit.</item>
/// </list>
/// Data only: add a row, not code. Essential is a real Fabric mod and is deliberately NOT here.
/// </summary>
public static class ClientCoreMods
{
    /// <param name="ModId">The fabric.mod.json id the crash window and Mods page use.</param>
    /// <param name="Ids">Every mod id this client's core jar can carry (lowercase).</param>
    /// <param name="FileNamePrefixes">Jar-name starts that identify it when the id can't be read (lowercase).</param>
    /// <param name="Packages">Code packages its classes live in: a stack frame inside one names this client.</param>
    public sealed record Client(string ModId, string Name, string[] Ids, string[] FileNamePrefixes, string[] Packages)
    {
        /// <summary>The plain reason it is never imported / what the crash window says.</summary>
        public string Reason => $"{Name}'s own client files only work inside {Name}, not in Origin, so they are not imported.";
    }

    public static readonly IReadOnlyList<Client> All =
    [
        new("feather", "Feather", ["feather", "feather-client", "featherclient"], ["feather-fabric", "feather-client"],
            ["net.digitalingot."]),
        new("lunar", "Lunar Client", ["lunar", "lunarclient", "lunar-client", "ichor"], ["lunarclient", "lunar-client", "ichor"],
            ["com.moonsworth.", "com.lunarclient."]),
        new("badlion", "Badlion Client", ["badlion", "badlionclient", "badlion-client"], ["badlion"],
            ["net.badlion."]),
        new("labymod", "LabyMod", ["labymod", "labymod4"], ["labymod"],
            ["net.labymod."]),
    ];

    /// <summary>The client a jar is the core of, by its mod id (or provided ids) first, then its file name; null for any other mod.</summary>
    public static Client? Find(string? modId, string? fileName, IEnumerable<string>? provides = null)
    {
        var ids = new List<string>();
        if (!string.IsNullOrEmpty(modId)) ids.Add(modId.ToLowerInvariant());
        if (provides != null) ids.AddRange(provides.Select(p => p.ToLowerInvariant()));
        foreach (var client in All)
            if (ids.Any(id => client.Ids.Contains(id, StringComparer.Ordinal))) return client;

        if (string.IsNullOrEmpty(fileName)) return null;
        var name = fileName.ToLowerInvariant();
        return All.FirstOrDefault(c => c.FileNamePrefixes.Any(p => name.StartsWith(p, StringComparison.Ordinal)));
    }

    /// <summary>The client whose code package a stack-frame class name (dotted, e.g. "net.digitalingot.feather.tk") sits in, or null.</summary>
    public static Client? FindByClassName(string className) =>
        All.FirstOrDefault(c => c.Packages.Any(p => className.StartsWith(p, StringComparison.Ordinal)));
}
