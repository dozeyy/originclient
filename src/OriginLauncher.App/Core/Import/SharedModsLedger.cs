using System.IO;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Import.Mods;

namespace OriginLauncher.App.Core.Import;

/// <summary>Where one mod of a plan stands against the shared mods folder (<see cref="SharedModsLedger.Decide"/>).</summary>
/// <param name="Holder">Who already has it (<see cref="SharedModsLedger.InOrigin"/> or a setup's label): nothing is copied. Null = it lands.</param>
/// <param name="Replaces">When it lands: Origin's copy it takes the place of (added first, then the old one removed; never rewritten in place).</param>
/// <param name="Note">Why: a plain sentence for the summary line, or null.</param>
/// <param name="Existing">When Origin already has it: the jar this import counts on instead (ImportJournal.SharedModsReliedOn).</param>
public sealed record ModLanding(string? Holder, string? Replaces = null, string? Note = null, string? Existing = null)
{
    public static readonly ModLanding Lands = new(Holder: null);
}

/// <summary>
/// What one Minecraft version's SHARED mods folder (instances/&lt;version&gt;/mods, Core/GameFolders/SharedMods) already
/// holds and what this run puts in it. Imports never make a profile: mods land in that one folder, every account of the
/// version plays them. Nothing in it is ever rewritten in place (a running game of another account may hold a jar): a
/// mod Origin already has stays as it is, a mod two setups of one run both bring lands once, from the first. Two cases
/// swap instead (add the imported jar, then remove Origin's): Origin's copy is turned off while the import's is on (a
/// turned-off jar doesn't load), or Origin's copy is older than the imported mods need (an old Fabric API under mods
/// that need a newer one won't boot) and the imported copy satisfies them and everything else in the folder.
/// </summary>
public sealed class SharedModsLedger
{
    /// <summary>Who already has a mod: Origin's folder (<see cref="InOrigin"/>) or the label of an earlier setup of this run.</summary>
    public const string InOrigin = "";

    // One jar of Origin's folder as it was read.
    private sealed record OriginJar(string Path, string? Version, bool Disabled, IReadOnlyList<ModDependency> Depends);

    private readonly Dictionary<string, string> _ids = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> _files = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, OriginJar> _originById = new(StringComparer.Ordinal);
    private readonly Dictionary<string, OriginJar> _originByFile = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<OriginJar> _originJars = new();
    // Mods this run lands turned on (id -> version), so a later check sees the folder as it will be, not as it was read.
    private readonly Dictionary<string, string?> _landed = new(StringComparer.Ordinal);

    /// <summary>The ledger of <paramref name="modsDir"/> as it is now: every jar's file name and mod id. A jar that can't be read still counts by name.</summary>
    public static SharedModsLedger Read(string modsDir)
    {
        var ledger = new SharedModsLedger();
        if (!Directory.Exists(modsDir)) return ledger;
        IEnumerable<string> files;
        try { files = Directory.EnumerateFiles(modsDir).OrderBy(p => p, StringComparer.OrdinalIgnoreCase).ToList(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return ledger; }
        // Turned-on jars first: when a mod is in the folder both on and off, the copy that loads is the one that counts.
        foreach (var path in files.OrderBy(p => IsDisabledName(Path.GetFileName(p))))
        {
            var name = Path.GetFileName(path);
            if (!IsModFile(name)) continue;
            // In use or unreadable gives null: its file name still keeps an import from landing on it.
            var info = ModFolderScanner.ReadInfo(path, readNested: false);
            var jar = new OriginJar(path, info?.Version, IsDisabledName(name), info?.Depends ?? Array.Empty<ModDependency>());
            ledger._originJars.Add(jar);
            if (ledger._files.TryAdd(FileKey(name), InOrigin)) ledger._originByFile[FileKey(name)] = jar;
            if (info is { Id: { Length: > 0 } id } && ledger._ids.TryAdd(id, InOrigin)) ledger._originById[id] = jar;
        }
        return ledger;
    }

    /// <summary>Who already has this mod (<see cref="InOrigin"/> or a setup's label); null when it is free to land.</summary>
    public string? Holder(ModDecision mod) => Decide(mod, Array.Empty<ModDependency>()).Holder;

    /// <summary>
    /// Whether <paramref name="mod"/> lands, and whether it takes the place of Origin's copy.
    /// <paramref name="needs"/>: what the imported mods require of this mod (their "depends" entries naming its id or
    /// anything it provides).
    /// </summary>
    public ModLanding Decide(ModDecision mod, IReadOnlyList<ModDependency> needs)
    {
        var landing = DecideCore(mod, needs);
        if (landing.Holder != InOrigin) return landing;
        var existing = mod.ModId is { Length: > 0 } id && _originById.TryGetValue(id, out var byId) ? byId.Path
            : mod.DestFileName is { } file && _originByFile.TryGetValue(FileKey(file), out var byFile) ? byFile.Path : null;
        return landing with { Existing = existing };
    }

    private ModLanding DecideCore(ModDecision mod, IReadOnlyList<ModDependency> needs)
    {
        var importOn = mod.DestFileName is { } dest && !IsDisabledName(dest);
        if (mod.ModId is { Length: > 0 } id && _ids.TryGetValue(id, out var byId))
        {
            if (byId != InOrigin || !_originById.TryGetValue(id, out var origin)) return new ModLanding(byId);
            var landing = Swap(mod, origin, importOn, needs);
            if (landing.Replaces == null || mod.DestFileName is not { } file) return landing;
            // A swap adds the new jar beside the old one, so it needs a name of its own: the very same file name
            // would be a rewrite in place, another jar's name would land on that jar.
            if (string.Equals(file, Path.GetFileName(origin.Path), StringComparison.OrdinalIgnoreCase))
                return new ModLanding(InOrigin, Note: landing.Note == TurnedOffNote ? null
                    : $"Origin has version {origin.Version ?? "unknown"}, older than your mods need; update it on the Mods page.");
            if (_files.ContainsKey(FileKey(file)) && !SameFileKey(file, origin.Path)) return new ModLanding(InOrigin);
            return landing;
        }
        if (mod.DestFileName is { } destName && _files.TryGetValue(FileKey(destName), out var byFile))
        {
            // Same file name, no readable id to compare: only a turned-off twin gives way to a turned-on import.
            if (byFile == InOrigin && importOn && _originByFile.TryGetValue(FileKey(destName), out var twin) && twin.Disabled
                && !_originJars.Any(j => !j.Disabled && SameFileKey(destName, j.Path)))
                return new ModLanding(null, twin.Path, TurnedOffNote);
            return new ModLanding(byFile);
        }
        return ModLanding.Lands;
    }

    internal const string TurnedOffNote = "imported turned on (Origin had it turned off)";

    private ModLanding Swap(ModDecision mod, OriginJar origin, bool importOn, IReadOnlyList<ModDependency> needs)
    {
        if (origin.Disabled && importOn) return new ModLanding(null, origin.Path, TurnedOffNote);
        if (origin.Disabled && !importOn) return new ModLanding(InOrigin);

        // Origin's copy is on. It stays unless the imported mods need a version it isn't and the imported one is.
        var unmet = needs.Where(n => !n.Predicate.IsSatisfiedBy(origin.Version)).ToList();
        if (unmet.Count == 0) return new ModLanding(InOrigin);
        if (!importOn || mod.Version == null || FabricVersion.Compare(mod.Version, origin.Version) <= 0
            || !needs.All(n => n.Predicate.IsSatisfiedBy(mod.Version)))
            return new ModLanding(InOrigin, Note: $"Origin has version {origin.Version ?? "unknown"}, older than your mods need ({unmet[0].Predicate.Display}); update it on the Mods page.");
        // Everything else in Origin's folder that needs this mod must still be happy with the imported version.
        var ids = new HashSet<string>(StringComparer.Ordinal) { mod.ModId! };
        foreach (var jar in _originJars)
        {
            if (jar.Disabled || ReferenceEquals(jar, origin)) continue;
            foreach (var dep in jar.Depends)
                if (ids.Contains(dep.Id) && !dep.Predicate.IsSatisfiedBy(mod.Version))
                    return new ModLanding(InOrigin, Note: $"Origin has version {origin.Version ?? "unknown"}, older than your mods need ({unmet[0].Predicate.Display}), and another of Origin's mods needs that one; update it on the Mods page.");
        }
        return new ModLanding(null, origin.Path, $"imported version {mod.Version}, replacing Origin's older {origin.Version ?? "copy"} (your mods need {unmet[0].Predicate.Display})");
    }

    /// <summary>Records that <paramref name="mod"/> lands in this run, brought by <paramref name="setupLabel"/> (taking over from Origin's copy when it replaces one).</summary>
    // Only called for a mod Decide let land: it is new to the folder, or it takes the place of Origin's copy.
    public void Add(ModDecision mod, string setupLabel)
    {
        if (mod.ModId is { Length: > 0 } id)
        {
            _ids[id] = setupLabel;
            _originById.Remove(id);
            if (mod.DestFileName is { } landedName && !IsDisabledName(landedName)) _landed[id] = mod.Version;
            else _landed.Remove(id);
        }
        if (mod.DestFileName is { } file)
        {
            _files[FileKey(file)] = setupLabel;
            _originByFile.Remove(FileKey(file));
        }
    }

    /// <summary>
    /// Every mod id the folder will have TURNED ON once this run is applied, with its version: Origin's turned-on jars
    /// (minus the ones an import replaced) plus what the run landed turned on. A turned-off jar loads nothing, so it
    /// isn't in here. Used to check that an imported mod's own dependencies are really there at the version it needs.
    /// </summary>
    public Dictionary<string, string?> PresentVersions()
    {
        var present = new Dictionary<string, string?>(StringComparer.Ordinal);
        foreach (var (id, jar) in _originById)
            if (!jar.Disabled) present[id] = jar.Version;
        foreach (var (id, version) in _landed) present[id] = version;
        return present;
    }

    /// <summary>
    /// The version of Origin's own TURNED-ON copy of <paramref name="id"/> (it may be null when the jar states none), as
    /// read: false when Origin has no copy or has it turned off. Used to put Origin's copy back in the picture when an
    /// imported mod that would have replaced it is turned off after all.
    /// </summary>
    public bool TryGetOriginVersion(string id, out string? version)
    {
        if (_originById.TryGetValue(id, out var jar) && !jar.Disabled) { version = jar.Version; return true; }
        version = null;
        return false;
    }

    private static bool IsModFile(string name) =>
        name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || name.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase);

    internal static bool IsDisabledName(string name) => name.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase);

    // "sodium.jar" and "sodium.jar.disabled" are one mod: the turned-off twin blocks the other.
    internal static string FileKey(string name) => IsDisabledName(name) ? name[..^".disabled".Length] : name;

    private static bool SameFileKey(string name, string path) =>
        string.Equals(FileKey(name), FileKey(Path.GetFileName(path)), StringComparison.OrdinalIgnoreCase);
}
