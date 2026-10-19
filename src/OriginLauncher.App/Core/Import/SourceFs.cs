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
        var name = Path.GetFileName(path);
        if (_guard.IsDenied(name))
        {
            if (!CredentialGuard.IsHarmlessNoise(name)) _skipped.Add(name);
            return false;
        }
        _onOpen?.Invoke(path);
        return true;
    }

    public Stream? OpenRead(string path)
    {
        if (!Allow(path)) return null;
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
    // look at the count and refuse, and the plan lists whatever was unreadable.

    private readonly SortedSet<string> _links = new(StringComparer.OrdinalIgnoreCase);
    private readonly SortedSet<string> _unreadable = new(StringComparer.OrdinalIgnoreCase);

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

