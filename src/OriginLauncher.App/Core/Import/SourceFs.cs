using System.IO;

namespace OriginLauncher.App.Core.Import;

// Every read of a source instance goes through here. It does three things no
// detector or migrator should have to remember:
//  * asks CredentialGuard first — a denied file is never opened, and its name
//    (only the name) is remembered for the summary;
//  * reads with FileShare.ReadWrite|Delete, so a file a running game holds open
//    (latest.log) can still be read and a source is never locked by the import;
//  * tells an optional hook which files were opened (the credential test asserts
//    no denied file ever shows up there).
// A source is read-only: nothing here writes.
public sealed class SourceFs
{
    private readonly CredentialGuard _guard;
    private readonly Action<string>? _onOpen;
    private readonly SortedSet<string> _skipped = new(StringComparer.OrdinalIgnoreCase);

    public SourceFs(CredentialGuard? guard = null, Action<string>? onOpen = null)
    {
        _guard = guard ?? CredentialGuard.Default;
        _onOpen = onOpen;
    }

    /// <summary>Names (never paths, never contents) of files left alone because they look like credentials.</summary>
    public IReadOnlyCollection<string> SkippedForSafety => _skipped;

    public CredentialGuard Guard => _guard;

    /// <summary>
    /// True when the file may be opened. Records it as opened (hook) — or, when
    /// denied, remembers its name for "skipped for safety".
    /// </summary>
    public bool Allow(string path)
    {
        if (!PassesGuard(path)) return false;
        if (!IsLinkFree(path))
        {
            _links.Add(Short(path));
            return false;
        }
        _onOpen?.Invoke(path);
        return true;
    }

    /// <summary>
    /// <see cref="Allow"/> for a file a walk of this reader just returned (<see cref="EnumerateFiles"/>): the walk
    /// already refused every link below the folder it started at, and its callers check that folder themselves
    /// (CopyTree, cache copies), so only the guard is asked here — no per-file link probe for every file in a world.
    /// </summary>
    internal bool AllowWalked(string path)
    {
        if (!PassesGuard(path)) return false;
        _onOpen?.Invoke(path);
        return true;
    }

    private bool PassesGuard(string path)
    {
        var name = Path.GetFileName(path);
        if (!_guard.IsDenied(name)) return true;
        if (!CredentialGuard.IsHarmlessNoise(name)) _skipped.Add(name);
        return false;
    }

    /// <summary>How many link probes (file-attribute reads) this reader has made: a test seam for the per-file cost.</summary>
    internal int LinkProbes { get; private set; }

    /// <summary>
    /// The gate for a Mojang game-file candidate that is about to be checked against Mojang's SHA-1 (game-file
    /// reuse). Its content is proven by the hash, so the broad credential NAME pattern adds nothing (it would
    /// refuse com/mojang/authlib/...): only the launchers' known credential stores are refused, and nothing is
    /// recorded as "skipped for safety". A link is never followed.
    /// </summary>
    public bool AllowVerifiedGameFile(string path)
    {
        if (CredentialGuard.IsKnownCredentialStore(Path.GetFileName(path))) return false;
        // Only the file itself: its SHA-1 proves the content, so a launcher that junctions .minecraftssets or
        // libraries somewhere else is fine — the instance-root rule (no link on any segment) would refuse every
        // candidate under it. The leaf is still never a link.
        if (IsLink(path)) return false;
        _onOpen?.Invoke(path);
        return true;
    }

    /// <summary>Opens a game-file candidate through <see cref="AllowVerifiedGameFile"/>; the caller verifies its SHA-1.</summary>
    public Stream? OpenVerifiedGameFile(string path) => AllowVerifiedGameFile(path) ? OpenShared(path) : null;

    public Stream? OpenRead(string path) => Allow(path) ? OpenShared(path) : null;

    private static Stream? OpenShared(string path)
    {
        try
        {
            return new FileStream(path, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete, 4096, useAsync: false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>A small file's bytes through the guard (null when refused or unreadable). Capped: a server list is a few KB.</summary>
    public byte[]? ReadBytes(string path, int maxBytes = 16 * 1024 * 1024)
    {
        using var stream = OpenRead(path);
        if (stream == null) return null;
        try
        {
            if (stream.Length > maxBytes) return null;
            using var copy = new MemoryStream();
            stream.CopyTo(copy);
            return copy.ToArray();
        }
        catch (IOException)
        {
            return null;
        }
    }

    public string? ReadText(string path)
    {
        using var stream = OpenRead(path);
        if (stream == null) return null;
        try
        {
            using var reader = new StreamReader(stream, System.Text.Encoding.UTF8, true);
            return reader.ReadToEnd();
        }
        catch (IOException)
        {
            return null;
        }
    }

    /// <summary>The first <paramref name="maxBytes"/> of a text file (a log's header), decoded as UTF-8.</summary>
    public string? ReadHeadText(string path, int maxBytes)
    {
        using var stream = OpenRead(path);
        if (stream == null) return null;
        try
        {
            var buffer = new byte[(int)Math.Min(maxBytes, Math.Max(0, stream.Length))];
            var read = 0;
            while (read < buffer.Length)
            {
                var n = stream.Read(buffer, read, buffer.Length - read);
                if (n <= 0) break;
                read += n;
            }
            return System.Text.Encoding.UTF8.GetString(buffer, 0, read);
        }
        catch (IOException)
        {
            return null;
        }
    }

    // ---- walking a source folder ----
    //
    // Links: the folder an enumeration STARTS at may itself be a link (a Prism setup that links saves/ or
    // mods/ to somewhere else is common, and it is exactly what the player's instance points at, so it is
    // followed). A link met INSIDE a walked folder (a junction in a world, a symlinked pack) is never
    // followed — it could lead to the player's whole profile, or loop — and is remembered by name so the
    // summary can say it was left out.
    //
    // Errors: a folder that can't be read is remembered (UnreadablePaths) and the walk carries on with the
    // rest, instead of silently returning nothing; callers that must not copy half of something (CopyTree)
    // compare UnreadableHits (every hit, not the short-name set) before and after their own walk
    // look at the count and refuse, and the plan lists whatever was unreadable.

    private readonly SortedSet<string> _links = new(StringComparer.OrdinalIgnoreCase);
    private readonly SortedSet<string> _unreadable = new(StringComparer.OrdinalIgnoreCase);
    private int _unreadableHits;

    /// <summary>
    /// How many unreadable folders/files walks have met so far: every hit counts, even one whose short name is
    /// already in <see cref="UnreadablePaths"/> (two worlds can both have an unreadable DIM-1/region). A caller
    /// that must not copy half of something compares this before and after its own walk.
    /// </summary>
    public int UnreadableHits => _unreadableHits;

    /// <summary>The source instance's game folder. When set, links are refused on any path segment below it (see <see cref="IsLinkFree"/>).</summary>
    public string? InstanceRoot { get; init; }

    /// <summary>
    /// A second game folder the setup reads worlds and screenshots from (a client's "game directory", Lunar's
    /// .minecraft). Paths below it get the same per-segment link check as paths below <see cref="InstanceRoot"/>.
    /// </summary>
    public string? SharedRoot { get; init; }

    // The instance's own first-level folders that launchers link on purpose (a Prism setup sharing saves/ or
    // mods/, or screenshots/ moved to a bigger drive): what the player's instance points at, so they are followed.
    // "shaders" is Lunar's name for shaderpacks/. Nothing deeper ever is.
    private static readonly HashSet<string> FollowedFirstLevel = new(StringComparer.OrdinalIgnoreCase)
    {
        "saves", "mods", "config", "resourcepacks", "shaderpacks", "screenshots", "shaders",
    };

    // The instance's own first-level settings FILES, followed like the folders above when a launcher links them
    // (a shared options.txt): each is read and parsed by the importer (or copied as the one known file it is),
    // never walked, so a link there can't lead the import anywhere else.
    private static readonly HashSet<string> FollowedFirstLevelFiles = new(StringComparer.OrdinalIgnoreCase)
    {
        "options.txt", "servers.dat", "optionsof.txt", "optionsshaders.txt",
    };

    private readonly HashSet<string> _linkFreeDirs = new(OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal);

    /// <summary>
    /// True when no segment of <paramref name="path"/> below the instance root is a link (junction, symlink),
    /// the instance's own first-level folders (saves, mods, config, resourcepacks, shaderpacks) excepted. A path
    /// outside the root, or with no root set, only has itself checked. A missing segment is not a link.
    /// </summary>
    public bool IsLinkFree(string path, string? root = null)
    {
        string full;
        try { full = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path)); }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException) { return false; }
        // Below the shared game folder (worlds, screenshots) and not below the instance: that folder is the root.
        root ??= SharedRoot != null && !IsBelow(InstanceRoot, full) && IsBelow(SharedRoot, full) ? SharedRoot : InstanceRoot;
        if (root != null)
        {
            var top = Path.TrimEndingDirectorySeparator(Path.GetFullPath(root));
            var rel = Path.GetRelativePath(top, full);
            if (rel == ".") return true; // the instance itself: what the player picked
            if (!Path.IsPathRooted(rel) && rel != ".." && !rel.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal))
            {
                var parts = rel.Split(new[] { Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar }, StringSplitOptions.RemoveEmptyEntries);
                var current = top;
                for (var i = 0; i < parts.Length; i++)
                {
                    current = Path.Combine(current, parts[i]);
                    if (i == 0 && FollowedFirstLevel.Contains(parts[0])) continue;
                    if (i == 0 && parts.Length == 1 && FollowedFirstLevelFiles.Contains(parts[0])) continue;
                    var isLast = i == parts.Length - 1;
                    if (!isLast && _linkFreeDirs.Contains(current)) continue;
                    if (IsLink(current)) return false;
                    if (!isLast) _linkFreeDirs.Add(current);
                }
                return true;
            }
        }
        return !IsLink(full);
    }

    private static bool IsBelow(string? root, string full)
    {
        if (root == null) return false;
        try
        {
            var rel = Path.GetRelativePath(Path.TrimEndingDirectorySeparator(Path.GetFullPath(root)), full);
            return rel != ".." && !Path.IsPathRooted(rel) && !rel.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal);
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return false;
        }
    }

    /// <summary>Remembers a link a caller found on a built path (not a walk) and left out, for the summary.</summary>
    public void NoteLink(string path) => _links.Add(Short(path));

    private bool IsLink(string path)
    {
        LinkProbes++;
        try
        {
            return (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false; // missing or unreadable: not a link (reading it fails on its own)
        }
    }

    /// <summary>Links found inside walked folders and left out (a short "folder/name", never the full path).</summary>
    public IReadOnlyCollection<string> SkippedLinks => _links;

    /// <summary>Folders or files a walk could not read (a short "folder/name").</summary>
    public IReadOnlyCollection<string> UnreadablePaths => _unreadable;

    // The last two path segments: enough for the player to recognise it, no machine-specific path.
    private static string Short(string path)
    {
        var name = Path.GetFileName(path.TrimEnd('/', '\\'));
        var parent = Path.GetFileName(Path.GetDirectoryName(path.TrimEnd('/', '\\')) ?? "");
        return parent.Length == 0 ? name : parent + "/" + name;
    }

    /// <summary>Files under <paramref name="directory"/> that may be read; denied ones are remembered by name. A missing folder is empty.</summary>
    public IEnumerable<string> EnumerateFiles(string directory, bool recursive)
    {
        foreach (var (path, isDirectory) in Walk(directory, recursive))
        {
            if (isDirectory) continue;
            var name = Path.GetFileName(path);
            if (_guard.IsDenied(name))
            {
                if (!CredentialGuard.IsHarmlessNoise(name)) _skipped.Add(name);
                continue;
            }
            yield return path;
        }
    }

    /// <summary>The sub-folders directly inside <paramref name="directory"/> (linked ones are left out and remembered).</summary>
    public IEnumerable<string> EnumerateDirectories(string directory) =>
        Walk(directory, recursive: false).Where(e => e.IsDirectory).Select(e => e.Path).ToList();

    private IEnumerable<(string Path, bool IsDirectory)> Walk(string directory, bool recursive)
    {
        if (!Directory.Exists(directory)) yield break;
        var pending = new Stack<string>();
        pending.Push(directory);
        while (pending.Count > 0)
        {
            var current = pending.Pop();
            List<string> entries;
            try
            {
                entries = Directory.EnumerateFileSystemEntries(current).ToList();
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                _unreadable.Add(Short(current));
                _unreadableHits++;
                continue;
            }
            foreach (var entry in entries)
            {
                FileAttributes attributes;
                try
                {
                    attributes = File.GetAttributes(entry);
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    _unreadable.Add(Short(entry));
                    _unreadableHits++;
                    continue;
                }
                if ((attributes & FileAttributes.ReparsePoint) != 0)
                {
                    _links.Add(Short(entry));
                    continue;
                }
                var isDirectory = (attributes & FileAttributes.Directory) != 0;
                if (isDirectory && recursive) pending.Push(entry);
                yield return (entry, isDirectory);
            }
        }
    }
}

