namespace OriginLauncher.App.Core.Import;

/// <summary>
/// What one run has already planned to write into ONE Origin game folder. A single-setup plan owns a private
/// ledger; a bulk import shares one per target folder across every setup of that version, so:
///  * a pack never lands twice: identical bytes from a later setup are "already brought over from X", a
///    different pack with the same name becomes "&lt;name&gt; (imported)";
///  * a config file is created by a later setup only where nothing earlier writes that path;
///  * a source world folder is copied at most once (two setups can share one game folder), and two different
///    worlds with the same name never merge;
///  * a Distant Horizons / Voxy cache is claimed once by its identity: the first setup in apply order brings it.
/// A later setup that defers to a claim keeps a stand-in (a <see cref="PlannedOp.Fallback"/> op with the claim's
/// <see cref="PlannedOp.ClaimKey"/>): if the claim's owner is skipped mid-run, the stand-in lands its copy instead.
/// Planning only: nothing here touches a disk.
/// </summary>
public sealed class DestinationLedger
{
    private static readonly StringComparer PathComparer =
        OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;

    private readonly Dictionary<string, Claim> _claims = new(PathComparer);
    private readonly Dictionary<string, WorldClaim> _worldSources = new(PathComparer);
    private readonly Dictionary<string, string> _caches = new(StringComparer.Ordinal);

    /// <summary>A destination someone in this run will write: where from (null for a composed file), whose setup, and that setup's key.</summary>
    public sealed record Claim(string? Source, string SetupLabel, bool IsDirectory, string? Owner = null);

    /// <summary>A source world folder this run copies, and where it lands.</summary>
    public sealed record WorldClaim(string SetupLabel, string Dest, string? Owner);

    public bool IsClaimed(string dest) => _claims.ContainsKey(dest);

    public Claim? ClaimOf(string dest) => _claims.TryGetValue(dest, out var claim) ? claim : null;

    /// <summary>Records that <paramref name="dest"/> will be written; the first claim of a path stays.</summary>
    public void Add(string dest, string? source, string setupLabel, bool isDirectory = false, string? owner = null) =>
        _claims.TryAdd(dest, new Claim(source, setupLabel, isDirectory, owner));

    /// <summary>
    /// True when this run has not copied the world folder <paramref name="sourceWorld"/> yet (and now claims it for
    /// <paramref name="dest"/>); else false with the first claim (who, and where it lands).
    /// </summary>
    public bool TryClaimWorldSource(string sourceWorld, string dest, string setupLabel, string? owner, out WorldClaim? first)
    {
        var key = Normalize(sourceWorld);
        if (_worldSources.TryGetValue(key, out first)) return false;
        _worldSources[key] = new WorldClaim(setupLabel, dest, owner);
        first = null;
        return true;
    }

    /// <summary>True when no earlier setup in this run brings the cache with this identity (and now claims it); else the setup that does.</summary>
    public bool TryClaimCache(string identity, string setupLabel, out string? firstSetup)
    {
        if (_caches.TryGetValue(identity, out var first))
        {
            firstSetup = first;
            return false;
        }
        _caches[identity] = setupLabel;
        firstSetup = null;
        return true;
    }

    /// <summary>The claim key of a destination path (case-folded where the file system is case-insensitive).</summary>
    public static string PathKey(string dest)
    {
        var full = Normalize(dest);
        return "path:" + (OperatingSystem.IsWindows() ? full.ToUpperInvariant() : full);
    }

    /// <summary>The claim key of a cache identity.</summary>
    public static string CacheKey(string identity) => "cache:" + identity;

    private static string Normalize(string path)
    {
        try { return System.IO.Path.TrimEndingDirectorySeparator(System.IO.Path.GetFullPath(path)); }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or System.IO.PathTooLongException) { return path; }
    }
}
