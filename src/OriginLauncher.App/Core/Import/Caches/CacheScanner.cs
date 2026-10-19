using System.IO;
using System.Text;

namespace OriginLauncher.App.Core.Import.Caches;

/// <summary>One Distant Horizons database: DistantHorizons.sqlite plus its -wal and -shm, in one folder.</summary>
public sealed record DhDatabase(CacheScope Scope, string Owner, string Dimension, string Dir)
{
    public const string MainName = "DistantHorizons.sqlite";
    public static readonly string[] GroupNames = { MainName, MainName + "-wal", MainName + "-shm" };

    public string MainPath => Path.Combine(Dir, MainName);
    public bool HasMain => File.Exists(MainPath);

    /// <summary>The group's files that exist, main first.</summary>
    public IReadOnlyList<string> Members => GroupNames.Select(n => Path.Combine(Dir, n)).Where(File.Exists).ToList();

    /// <summary>A journal file whose database is missing: it means nothing alone and is never copied.</summary>
    public bool LoneWal => !HasMain && File.Exists(MainPath + "-wal");

    public string DimensionLabel => Dimension.Length == 0 ? "overworld" : Dimension;
}

/// <summary>A multiplayer server's whole folder under Distant_Horizons_server_data (one sub-folder per level).</summary>
public sealed record DhServerFolder(string Folder, string Dir, IReadOnlyList<DhDatabase> Levels)
{
    /// <summary>The folder name as the player knows it (the mod percent-escapes it): display only, never used to find the folder.</summary>
    public string DisplayName
    {
        get
        {
            try { return Uri.UnescapeDataString(Folder); }
            catch (UriFormatException) { return Folder; }
        }
    }
}

/// <summary>A Voxy base folder: config.json plus one sub-folder per world id (each with a RocksDB under storage/).</summary>
public sealed record VoxyBase(CacheScope Scope, string Owner, string Dir);

/// <summary>Something found that is never imported, and why.</summary>
public sealed record NeverImport(CacheKind Kind, string Name, string Reason);

public sealed class CacheScan
{
    public List<DhDatabase> DhSinglePlayer { get; } = new();
    public List<DhServerFolder> DhServers { get; } = new();
    public List<VoxyBase> Voxy { get; } = new();
    public List<NeverImport> Never { get; } = new();
}

// Finds the Distant Horizons and Voxy caches in a source game folder (D20). Read-only, through the guard.
//   Distant Horizons, single player: <world>/data/DistantHorizons.sqlite, <world>/DIM-1/data/..., <world>/DIM1/data/...
//     and custom dimensions <world>/dimensions/<namespace>/<path>/data/...
//   Distant Horizons, multiplayer:  Distant_Horizons_server_data/<server>/<level>/DistantHorizons.sqlite
//     (server folder named by the mod; the literal "REPLAY" is a replay recording, never imported)
//   Voxy, single player: <world>/voxy        Voxy, multiplayer: .voxy/saves/<server address with ':' as '_'>
//     (realms and an "UNKNOWN" folder have no stable identity: never imported)
public static class CacheScanner
{
    public const string DhServerRoot = "Distant_Horizons_server_data";

    // Folders inside a dimension that hold terrain, not other dimensions: never searched for caches.
    private static readonly HashSet<string> Heavy = new(StringComparer.OrdinalIgnoreCase)
    {
        "data", "region", "entities", "poi", "playerdata", "advancements", "stats", "voxy",
    };

    public static CacheScan Scan(string gameDir, SourceFs fs, CancellationToken ct)
    {
        var scan = new CacheScan();

        foreach (var world in fs.EnumerateDirectories(Path.Combine(gameDir, "saves")).OrderBy(w => w, StringComparer.OrdinalIgnoreCase))
        {
            ct.ThrowIfCancellationRequested();
            var name = Path.GetFileName(world);
            AddDh(scan, fs, name, "", world);
            AddDh(scan, fs, name, "DIM-1", Path.Combine(world, "DIM-1"));
            AddDh(scan, fs, name, "DIM1", Path.Combine(world, "DIM1"));
            foreach (var dimension in CustomDimensions(world, fs, 0))
                AddDh(scan, fs, name, Path.GetRelativePath(world, dimension).Replace('\\', '/'), dimension);
            var voxy = Path.Combine(world, "voxy");
            if (Directory.Exists(voxy) && LinkFree(fs, voxy)) scan.Voxy.Add(new VoxyBase(CacheScope.SinglePlayer, name, voxy));
        }

        var dhRoot = Path.Combine(gameDir, DhServerRoot);
        foreach (var server in (LinkFree(fs, dhRoot) ? fs.EnumerateDirectories(dhRoot) : Enumerable.Empty<string>()).OrderBy(s => s, StringComparer.OrdinalIgnoreCase))
        {
            ct.ThrowIfCancellationRequested();
            var folder = Path.GetFileName(server);
            if (folder == "REPLAY")
            {
                scan.Never.Add(new NeverImport(CacheKind.DistantHorizons, folder, "Distant Horizons replay data is never imported."));
                continue;
            }
            var levels = fs.EnumerateDirectories(server)
                .Select(level => new DhDatabase(CacheScope.Multiplayer, folder, Path.GetFileName(level), level))
                .Where(db => db.HasMain || db.LoneWal)
                .OrderBy(db => db.Dimension, StringComparer.OrdinalIgnoreCase)
                .ToList();
            if (levels.Count > 0) scan.DhServers.Add(new DhServerFolder(folder, server, levels));
        }

        var voxyRoot = Path.Combine(gameDir, ".voxy", "saves");
        foreach (var entry in (LinkFree(fs, voxyRoot) ? fs.EnumerateDirectories(voxyRoot) : Enumerable.Empty<string>()).OrderBy(s => s, StringComparer.OrdinalIgnoreCase))
        {
            var name = Path.GetFileName(entry);
            if (name.Equals("UNKNOWN", StringComparison.OrdinalIgnoreCase))
                scan.Never.Add(new NeverImport(CacheKind.Voxy, name, "Voxy data with no known server has no identity, so it is never imported."));
            else if (name.StartsWith("realms", StringComparison.OrdinalIgnoreCase))
                scan.Never.Add(new NeverImport(CacheKind.Voxy, name, "Voxy data for a Realm is never imported."));
            else
                scan.Voxy.Add(new VoxyBase(CacheScope.Multiplayer, name, entry));
        }
        return scan;
    }

    private static void AddDh(CacheScan scan, SourceFs fs, string world, string dimension, string dimensionDir)
    {
        var db = new DhDatabase(CacheScope.SinglePlayer, world, dimension, Path.Combine(dimensionDir, "data"));
        if (!db.HasMain && !db.LoneWal) return;
        // Built by name, not found by a walk: a linked dimension, data folder or database file is never followed.
        if (!LinkFree(fs, db.Dir) || db.Members.Any(m => !LinkFree(fs, m)) || !LinkFree(fs, db.MainPath + "-wal")) return;
        scan.DhSinglePlayer.Add(db);
    }

    // A folder or file reached by a built path (not a walk, which skips links itself) is used only when no
    // segment below the instance root is a link; one that is, is remembered as a left-out link.
    private static bool LinkFree(SourceFs fs, string path)
    {
        if (fs.IsLinkFree(path)) return true;
        fs.NoteLink(path);
        return false;
    }

    // dimensions/<ns>/<path...>: folders only (never the region files), a few levels deep.
    private static IEnumerable<string> CustomDimensions(string world, SourceFs fs, int depth)
    {
        var root = Path.Combine(world, "dimensions");
        if (!Directory.Exists(root) || !LinkFree(fs, root)) yield break;
        var stack = new Stack<(string Dir, int Depth)>();
        stack.Push((root, 0));
        while (stack.Count > 0)
        {
            var (dir, level) = stack.Pop();
            foreach (var child in fs.EnumerateDirectories(dir))
            {
                if (Heavy.Contains(Path.GetFileName(child))) continue;
                if (Directory.Exists(Path.Combine(child, "data"))) yield return child;
                if (level < 3) stack.Push((child, level + 1));
            }
        }
    }
}

/// <summary>Read-only probes of a cache before and after it is copied (D22, D23).</summary>
public static class CacheProbes
{
    private static readonly byte[] SqliteMagic = Encoding.ASCII.GetBytes("SQLite format 3\0");

    /// <summary>
    /// True when something else holds the file open (the game, a tool): an exclusive open fails. Nothing is
    /// read or written. A missing file is not locked.
    /// </summary>
    public static bool IsLocked(string path)
    {
        if (!File.Exists(path)) return false;
        try
        {
            using var _ = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.None);
            return false;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return true;
        }
    }

    /// <summary>
    /// Reads only the first 100 bytes (shared, so a running game isn't disturbed): is it "SQLite format 3"?
    /// No SQLite engine is ever opened — Distant Horizons has no file-level schema version to check anyway;
    /// compatibility is judged from the mod's version.
    /// </summary>
    public static bool LooksLikeSqlite(string path, SourceFs fs, out bool walMode)
    {
        walMode = false;
        using var stream = fs.OpenRead(path);
        if (stream == null) return false;
        try
        {
            var header = new byte[100];
            var read = 0;
            while (read < header.Length)
            {
                var n = stream.Read(header, read, header.Length - read);
                if (n <= 0) break;
                read += n;
            }
            if (read < 20 || !header.AsSpan(0, 16).SequenceEqual(SqliteMagic)) return false;
            walMode = header[18] == 2 && header[19] == 2;
            return true;
        }
        catch (IOException)
        {
            return false;
        }
    }
}
