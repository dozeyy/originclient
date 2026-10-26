using System.IO;

using OriginLauncher.App.Core.Components;

namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// What a profile launch changes on Origin's side so the player's own copies of mods Origin also ships load instead
/// of Origin's (Will, 2026-10-25: "their mods list takes priority"). Nothing in the shared mods folder is touched:
/// <see cref="WithheldPaths"/> are simply not put in the per-launch folder, and <see cref="DisabledIds"/> go to the
/// loader as <c>-Dfabric.debug.disableModIds</c> (Fabric Loader 0.15.0 to 0.20.1 skip every candidate with such an
/// id, top-level or jar-in-jar, ModDiscoverer "Skipping disabled mod").
/// </summary>
public sealed record PlayerOverridePlan
{
    public static readonly PlayerOverridePlan Empty = new();

    /// <summary>Origin's top-level jars this launch leaves out (full paths).</summary>
    public IReadOnlySet<string> WithheldPaths { get; init; } = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

    /// <summary>The player's jars this launch leaves out because Origin's copy has to stay (full paths).</summary>
    public IReadOnlySet<string> SetAsidePaths { get; init; } = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

    /// <summary>Ids nested in Origin's jar that nobody may load this launch (the loader skips them).</summary>
    public IReadOnlyList<string> DisabledIds { get; init; } = Array.Empty<string>();

    /// <summary>Ids taken out of Origin's jars' nested lists for the compatibility check (the player's or disabled).</summary>
    public IReadOnlySet<string> StrippedIds { get; init; } = new HashSet<string>(StringComparer.Ordinal);

    /// <summary>Ids where the player's copy loads instead of Origin's.</summary>
    public IReadOnlyList<string> OverriddenIds { get; init; } = Array.Empty<string>();

    /// <summary>A vetted Sodium + Iris pair (ConfigurationCatalog) that matches the player's copy, to pin for this launch.</summary>
    public string? PairId { get; init; }

    /// <summary>True when the player's copy decides Sodium or Iris (the launch must not pick a configuration of its own).</summary>
    public bool PlayerDecidesPair => PairId != null || OverriddenIds.Any(id => id is "sodium" or "iris");

    /// <summary>One plain line per decision, for the compatibility log.</summary>
    public IReadOnlyList<string> Log { get; init; } = Array.Empty<string>();

    /// <summary>The lines of <see cref="Log"/> the player should read: one of Origin's mods kept out, or a copy of theirs not used.</summary>
    public IReadOnlyList<string> Notices { get; init; } = Array.Empty<string>();

    public bool IsEmpty => WithheldPaths.Count == 0 && SetAsidePaths.Count == 0 && DisabledIds.Count == 0
        && StrippedIds.Count == 0 && PairId == null;

    /// <summary>The loader switch for <see cref="DisabledIds"/>, or null when there are none.</summary>
    public string? JvmArgument => DisabledIds.Count == 0 ? null : "-Dfabric.debug.disableModIds=" + string.Join(",", DisabledIds);

    /// <summary>True when the jar at <paramref name="path"/> stays out of this launch.</summary>
    public bool Skips(string path)
    {
        if (WithheldPaths.Count == 0 && SetAsidePaths.Count == 0) return false;
        var full = Full(path);
        return WithheldPaths.Contains(full) || SetAsidePaths.Contains(full);
    }

    /// <summary><paramref name="info"/> without the nested copies the loader will not use this launch.</summary>
    public ModInfo Strip(ModInfo info) =>
        StrippedIds.Count == 0 || !info.Nested.Any(n => StrippedIds.Contains(n.Id)) ? info
        : info with { Nested = info.Nested.Where(n => !StrippedIds.Contains(n.Id)).ToList() };

    /// <summary>
    /// The jars that really load once the plan is applied: Origin's withheld jars and the player's set-aside ones out,
    /// and Origin's nested copies the loader will not use taken out of its jars' metadata.
    /// </summary>
    public List<ModFile> ApplyTo(IEnumerable<ModFile> entries) => IsEmpty ? entries.ToList()
        : entries.Where(e => !Skips(e.Path)).Select(e => e.Role == ModRole.Managed ? e with { Info = Strip(e.Info) } : e).ToList();

    /// <summary>Stable text of the plan (part of the launch check's fingerprint).</summary>
    public string Key => IsEmpty ? "" : string.Join(";", OverriddenIds) + "|" + string.Join(";", DisabledIds) + "|" + PairId;

    internal static string Full(string path)
    {
        try { return Path.GetFullPath(path); }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException) { return path; }
    }
}

/// <summary>
/// The rule "the player's copy wins" for a mod profile, pure (decided on already-read metadata; unit tested):
///   * a mod in the profile with an id Origin ships (top-level or nested in Origin's jar) loads instead of Origin's,
///     when it is a readable Fabric mod for this Minecraft version and a different version than Origin's;
///   * never for Origin's own mod (originclient); Fabric API only when the player's is newer (Origin's mods are built
///     against Origin's Fabric API, and a newer one keeps every older module);
///   * Sodium and Iris move as a pair (docs/overhaul/spike-sodium-iris.md: Sodium 0.6.5 with Iris 1.8.8 crashes at
///     boot and the loader does not refuse it). The player's Sodium alone: a vetted pair with that Sodium is used
///     (<see cref="PlayerOverridePlan.PairId"/>), otherwise Origin's Iris is kept out (shaders off, the game starts).
///     The player's Iris alone: Origin's pair stays, unless a vetted pair has that Iris;
///   * an Origin mod that declares it does not work with the player's version is kept out with it; if that mod is
///     Origin's own or Fabric API, the player's copy is set aside instead and Origin's stays.
/// A top-level jar beats a nested one in Fabric Loader (ModPrioSorter puts root mods first; read for 0.19.3 and
/// 0.20.1), so the player's copy of a mod nested in Origin's jar needs nothing more than being there.
/// </summary>
public static class PlayerOverrides
{
    // Origin's own mod and the platform: never replaced from a profile.
    private static readonly HashSet<string> Never = new(StringComparer.Ordinal) { "originclient", "minecraft", "java", "fabricloader" };

    // Mods whose older copy would leave Origin's mods short (they are built against Origin's): only a newer one wins.
    private static readonly HashSet<string> NewerOnly = new(StringComparer.Ordinal) { "fabric-api", "fabric" };

    // Origin mods that may not be kept out of a launch to make room for the player's copy.
    private static readonly HashSet<string> Core = new(StringComparer.Ordinal) { "originclient", "fabric-api", "fabric" };

    private sealed record OriginMod(string Id, string? Version, string? Name, ModFile Host, bool Nested,
        IReadOnlyList<ModDependency> Depends, IReadOnlyList<ModDependency> Breaks);

    /// <summary>Decides the plan for one profile launch.</summary>
    /// <param name="origin">Origin's jars in the launch (role Managed), metadata read.</param>
    /// <param name="player">The profile's jars, metadata read.</param>
    /// <param name="vetted">The version's vetted configurations (ConfigurationCatalog.For), or null.</param>
    public static PlayerOverridePlan Plan(
        string mcVersion, IReadOnlyList<ModFile> origin, IReadOnlyList<ModFile> player,
        IReadOnlyList<VettedConfiguration>? vetted = null)
    {
        if (mcVersion == "1.8.9" || player.Count == 0 || origin.Count == 0) return PlayerOverridePlan.Empty; // Forge: no ids to weigh

        // Origin's copies by id: top-level first (it is what Origin loads), then what its jars nest.
        var originMods = new Dictionary<string, OriginMod>(StringComparer.Ordinal);
        foreach (var f in origin)
            if (!f.Info.ReadFailed && f.Info.Id is { } id && f.Enabled)
                originMods.TryAdd(id, new OriginMod(id, f.Info.Version, f.Info.Name, f, false, f.Info.Depends, f.Info.Breaks));
        foreach (var f in origin)
            if (!f.Info.ReadFailed && f.Enabled)
                foreach (var n in f.Info.Nested)
                    originMods.TryAdd(n.Id, new OriginMod(n.Id, n.Version, n.Name, f, true, n.Depends, n.Breaks));

        var log = new List<string>();
        var notices = new List<string>();
        var setAside = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var wins = new SortedDictionary<string, ModFile>(StringComparer.Ordinal);

        // Newest first, so the copy the loader would pick among the player's own duplicates is the one weighed.
        foreach (var p in player.Where(p => p.Enabled).OrderByDescending(p => p.Info.Version, VersionOrder.Instance))
        {
            var info = p.Info;
            if (info.ReadFailed || info.Loader != ModLoader.Fabric || info.Id is not { } id) continue;
            if (!originMods.TryGetValue(id, out var mine) || wins.ContainsKey(id)) continue;
            var name = info.Name ?? id;
            if (Never.Contains(id))
            {
                SetAside(p, $"{p.FileName}: Origin's own mod always comes from Origin; this copy is not loaded.");
                continue;
            }
            if (info.MinecraftRange is { } range && !range.IsSatisfiedBy(mcVersion))
            {
                SetAside(p, $"{name} {info.Version}: made for Minecraft {range.Display}, not {mcVersion}; Origin's copy is used.");
                continue;
            }
            if (SameVersion(info.Version, mine.Version))
            {
                SetAside(p, null); // the same mod: Origin's copy loads, nothing changes
                continue;
            }
            if (NewerOnly.Contains(id) && !IsNewer(info.Version, mine.Version))
            {
                SetAside(p, $"{name} {info.Version}: older than Origin's {mine.Version}, which Origin's mods need; Origin's copy is used.");
                continue;
            }
            wins[id] = p;
        }

        var withheld = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var disabled = new List<string>();
        var stripped = new HashSet<string>(StringComparer.Ordinal);
        string? pairId = null;

        // Sodium and Iris: one side from the player and the other from Origin only when a vetted pair says so.
        if (originMods.ContainsKey("sodium") && originMods.ContainsKey("iris")
            && wins.ContainsKey("sodium") != wins.ContainsKey("iris"))
        {
            var mineId = wins.ContainsKey("sodium") ? "sodium" : "iris";
            var otherId = mineId == "sodium" ? "iris" : "sodium";
            var mineVersion = wins[mineId].Info.Version;
            var pair = vetted?.FirstOrDefault(c => c.Files.Any(f => f.ModId == mineId && SameVersion(f.ModVersion, mineVersion)));
            var partner = pair?.Files.FirstOrDefault(f => f.ModId == otherId);
            if (partner != null && SameVersion(partner.ModVersion, originMods[otherId].Version))
            {
                // Origin's side already is the vetted partner (the pair's files are in this launch).
            }
            else if (pair != null && partner != null)
            {
                pairId = pair.PairId;
                log.Add($"Your {Display(mineId)} {mineVersion} has a tested partner ({Display(otherId)} {partner.ModVersion}): that pair is used.");
                KeepOut(originMods[otherId], null); // until the vetted partner is in the launch, never the mismatched one
            }
            else if (mineId == "sodium")
            {
                KeepOut(originMods["iris"],
                    $"Shaders are off for this profile: Origin has not tested an Iris with your Sodium {mineVersion}. Add a matching Iris to the profile to turn them back on.");
            }
            else
            {
                SetAside(wins["iris"],
                    $"Your Iris {mineVersion} needs the Sodium it was made for: Origin's Sodium and Iris are used. Add that Sodium to the profile to use your Iris.");
                wins.Remove("iris");
            }
        }

        // Origin's mods that say they do not work with the player's version go with it; Origin's core never does.
        foreach (var (id, p) in wins.ToList())
        {
            var version = p.Info.Version;
            var marked = new Dictionary<string, OriginMod>(StringComparer.Ordinal);
            bool Gone(string other) => (marked.ContainsKey(other) || stripped.Contains(other) || IsWithheld(other)) && !wins.ContainsKey(other);
            bool changed;
            do
            {
                changed = false;
                foreach (var m in originMods.Values)
                {
                    if (m.Id == id || wins.ContainsKey(m.Id) || marked.ContainsKey(m.Id) || Gone(m.Id)) continue;
                    var refuses = m.Depends.Any(d => d.Id == id && !d.IsSatisfiedBy(version))
                        || m.Breaks.Any(d => d.Id == id && d.IsSatisfiedBy(version))
                        || m.Depends.Any(d => d.Id != id && Gone(d.Id) && !wins.ContainsKey(d.Id) && d.Id != "minecraft");
                    if (refuses) { marked[m.Id] = m; changed = true; }
                }
            } while (changed);

            if (marked.Keys.FirstOrDefault(Core.Contains) is { } core)
            {
                SetAside(p, $"{p.Info.Name ?? id} {version}: Origin's {Display(core)} needs its own copy; Origin's copy is used.");
                wins.Remove(id);
                continue;
            }
            foreach (var m in marked.Values)
                KeepOut(m, $"Origin's {m.Name ?? m.Id} is off for this profile: it does not work with your {p.Info.Name ?? id} {version}.");
        }

        foreach (var (id, p) in wins)
        {
            var mine = originMods[id];
            if (mine.Nested) stripped.Add(id);
            else withheld.Add(PlayerOverridePlan.Full(mine.Host.Path));
            // A top-level copy may also sit nested in another of Origin's jars: the player's top-level one still wins.
            foreach (var f in origin)
                if (f.Info.Nested.Any(n => n.Id == id)) stripped.Add(id);
            log.Add($"{p.Info.Name ?? id} {p.Info.Version} from the profile is used instead of Origin's {mine.Version}.");
        }

        return new PlayerOverridePlan
        {
            WithheldPaths = withheld,
            SetAsidePaths = setAside,
            DisabledIds = disabled,
            StrippedIds = stripped,
            OverriddenIds = wins.Keys.ToList(),
            PairId = pairId,
            Log = log,
            Notices = notices,
        };

        void SetAside(ModFile p, string? why)
        {
            setAside.Add(PlayerOverridePlan.Full(p.Path));
            if (why != null) { log.Add(why); notices.Add(why); }
        }

        bool IsWithheld(string id) => originMods.TryGetValue(id, out var m) && !m.Nested && withheld.Contains(PlayerOverridePlan.Full(m.Host.Path));

        void KeepOut(OriginMod m, string? why)
        {
            if (m.Nested)
            {
                if (!disabled.Contains(m.Id)) disabled.Add(m.Id);
                stripped.Add(m.Id);
            }
            else withheld.Add(PlayerOverridePlan.Full(m.Host.Path));
            if (why != null) { log.Add(why); notices.Add(why); }
        }
    }

    /// <summary>
    /// True when the player's copy of <paramref name="id"/> may replace Origin's (<paramref name="originVersion"/>) in a
    /// profile, by id and version alone (import and drop-a-jar ask this before the launch decides with every file).
    /// </summary>
    public static bool MayReplace(string id, string? playerVersion, string? originVersion) =>
        !Never.Contains(id) && !SameVersion(playerVersion, originVersion)
        && (!NewerOnly.Contains(id) || IsNewer(playerVersion, originVersion));

    // Newer as versions; an unknown version on either side is never "newer".
    private static bool IsNewer(string? a, string? b) =>
        FabricVersion.TryParse(a, out var x) && FabricVersion.TryParse(b, out var y) && x.CompareTo(y) > 0;

    internal static bool SameVersion(string? a, string? b) =>
        string.Equals(a, b, StringComparison.OrdinalIgnoreCase)
        || (a != null && b != null && FabricVersion.TryParse(a, out var x) && FabricVersion.TryParse(b, out var y) && x.CompareTo(y) == 0);

    private static string Display(string id) => id switch { "sodium" => "Sodium", "iris" => "Iris", "fabric-api" or "fabric" => "Fabric API", _ => id };

    private sealed class VersionOrder : IComparer<string?>
    {
        public static readonly VersionOrder Instance = new();
        public int Compare(string? a, string? b) => FabricVersion.Compare(a, b);
    }
}
