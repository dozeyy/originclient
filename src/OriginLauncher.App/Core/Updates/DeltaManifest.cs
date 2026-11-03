using System.IO;
using System.Text.Json;

namespace OriginLauncher.App.Core.Updates;

// The release's file list for changed-files-only updates ("delta"): OriginLauncher-win-x64.files.json, written by
// tools/release/zip_manifest.py from the final release zip and published with its own .sha256 sidecar (the same trust
// model as the zip's .sha256). For each file in the zip: its path, size and SHA-256, and where its compressed bytes sit
// in the zip, so DeltaStager can fetch just that slice with an HTTP Range request. Pure (bytes in, records out).
//
// Parse refuses anything it doesn't fully understand (DeltaUnavailableException): an unknown format, a list that names
// another zip or another zip hash than the one the launcher already trusts, an unsafe path, a duplicate, overlapping
// byte ranges, an unknown compression method. A refusal only means "download the whole zip instead", never an error
// the player sees.
public sealed record DeltaFile(string Path, long Size, string Sha256, long Offset, long CompressedSize, int Method)
{
    public const int Stored = 0;
    public const int Deflate = 8;
}

public sealed record DeltaManifest(string Zip, long ZipSize, string ZipSha256, IReadOnlyList<DeltaFile> Files, IReadOnlyList<string> Dirs)
{
    public const int Format = 1;

    /// <summary>SHA-256 of zero bytes.</summary>
    public const string EmptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    // A real list is ~4 KB (29 files today); anything near this is not ours.
    public const int MaxBytes = 4 * 1024 * 1024;
    private const int MaxEntries = 20_000;

    /// <summary>
    /// Parses and validates the list. <paramref name="zipName"/> and <paramref name="zipSha256"/> are the zip this
    /// launcher is about to update from and the hash it verified for it (the zip's .sha256): the list must describe
    /// exactly that zip, so a stale or mixed-up list can never be used. Files come back sorted by offset.
    /// </summary>
    public static DeltaManifest Parse(ReadOnlySpan<byte> json, string zipName, string zipSha256)
    {
        if (json.Length == 0 || json.Length > MaxBytes) throw new DeltaUnavailableException("the file list is empty or too big");
        Raw? raw;
        try
        {
            raw = JsonSerializer.Deserialize<Raw>(json, JsonOptions);
        }
        catch (JsonException e)
        {
            throw new DeltaUnavailableException("the file list is not valid JSON", e);
        }
        if (raw == null || raw.Format != Format) throw new DeltaUnavailableException($"unknown file-list format {raw?.Format}");
        if (!string.Equals(raw.Zip, zipName, StringComparison.OrdinalIgnoreCase))
            throw new DeltaUnavailableException($"the file list describes {raw.Zip}, not {zipName}");
        if (raw.ZipSha256 == null || !string.Equals(raw.ZipSha256, zipSha256, StringComparison.OrdinalIgnoreCase))
            throw new DeltaUnavailableException("the file list describes a different zip (its SHA-256 differs)");
        if (raw.ZipSize <= 0) throw new DeltaUnavailableException("the file list has no zip size");
        if (raw.Files is not { Count: > 0 } || raw.Files.Count > MaxEntries)
            throw new DeltaUnavailableException("the file list has no files (or far too many)");

        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var files = new List<DeltaFile>(raw.Files.Count);
        foreach (var f in raw.Files)
        {
            if (f == null || f.Path == null || !IsSafeRelative(f.Path)) throw new DeltaUnavailableException($"unsafe path in the file list: {f?.Path}");
            if (!names.Add(f.Path)) throw new DeltaUnavailableException($"duplicate path in the file list: {f.Path}");
            if (f.Sha256 is not { Length: 64 } sha || !IsHex(sha)) throw new DeltaUnavailableException($"{f.Path}: no SHA-256");
            if (f.Method is not (DeltaFile.Stored or DeltaFile.Deflate)) throw new DeltaUnavailableException($"{f.Path}: compression method {f.Method}");
            if (f.Size < 0 || f.Csize < 0 || f.Offset < 0 || f.Offset > raw.ZipSize - f.Csize)
                throw new DeltaUnavailableException($"{f.Path}: sizes or offset outside the zip");
            if (f.Method == DeltaFile.Stored && f.Csize != f.Size) throw new DeltaUnavailableException($"{f.Path}: stored but sizes differ");
            // A file with bytes always has compressed bytes; an empty one must carry the empty SHA-256 (it is written, not fetched).
            if (f.Size > 0 && f.Csize == 0) throw new DeltaUnavailableException($"{f.Path}: has no compressed bytes");
            if (f.Size == 0 && !string.Equals(sha, EmptySha256, StringComparison.OrdinalIgnoreCase))
                throw new DeltaUnavailableException($"{f.Path}: empty but its SHA-256 is not the empty one");
            files.Add(new DeltaFile(f.Path, f.Size, sha.ToLowerInvariant(), f.Offset, f.Csize, f.Method));
        }

        // A file can't also be a folder another file sits in ("a" and "a/b"): the stage would be ambiguous.
        foreach (var f in files)
            for (var i = f.Path.IndexOf('/'); i > 0; i = f.Path.IndexOf('/', i + 1))
                if (names.Contains(f.Path[..i])) throw new DeltaUnavailableException($"{f.Path[..i]} is both a file and a folder");

        files.Sort((a, b) => a.Offset.CompareTo(b.Offset));
        for (var i = 1; i < files.Count; i++)
            if (files[i - 1].Offset + files[i - 1].CompressedSize > files[i].Offset)
                throw new DeltaUnavailableException($"{files[i].Path}: overlaps {files[i - 1].Path} in the zip");

        var dirs = new List<string>();
        foreach (var d in raw.Dirs ?? new List<string?>())
        {
            if (d == null || !IsSafeRelative(d) || names.Contains(d)) throw new DeltaUnavailableException($"unsafe folder in the file list: {d}");
            dirs.Add(d);
        }
        return new DeltaManifest(raw.Zip!, raw.ZipSize, raw.ZipSha256.ToLowerInvariant(), files, dirs);
    }

    /// <summary>
    /// A plain relative path with "/" separators that can only land inside the folder it is joined to: no root, no
    /// drive, no "." or ".." segment (nor any segment ending in a dot or space), no Windows device name, no empty segment, no backslash, and none of the characters Windows refuses in a
    /// file name. tools/release/zip_manifest.py refuses the same set when it writes the list.
    /// </summary>
    public static bool IsSafeRelative(string path)
    {
        if (path.Length is 0 or > 400 || path[0] == '/' || path[^1] == '/') return false;
        foreach (var c in path)
            if (c < 0x20 || c is '\\' or ':' or '<' or '>' or '"' or '|' or '?' or '*') return false;
        // A segment ending in "." or " " is trimmed by Windows, so "a.dll." would land on "a.dll": refused. A Windows
        // device name (CON, NUL, COM1, LPT1, ... with or without an extension) opens the device, not a file: refused.
        foreach (var segment in path.Split('/'))
            if (segment.Length == 0 || segment[^1] is '.' or ' ' || IsDeviceName(segment)) return false;
        return true;
    }

    private static bool IsDeviceName(string segment)
    {
        var dot = segment.IndexOf('.');
        var stem = (dot < 0 ? segment : segment[..dot]).TrimEnd(' ').ToUpperInvariant();
        return stem is "CON" or "PRN" or "AUX" or "NUL" or "CONIN$" or "CONOUT$"
               || (stem.Length == 4 && (stem.StartsWith("COM", StringComparison.Ordinal) || stem.StartsWith("LPT", StringComparison.Ordinal))
                   && stem[3] is >= '0' and <= '9' or '\u00B9' or '\u00B2' or '\u00B3');
    }

    /// <summary>
    /// <paramref name="relative"/> under <paramref name="root"/>, or an exception when it would leave it (a second guard
    /// behind <see cref="IsSafeRelative"/>: the full path must still start with the root's).
    /// </summary>
    public static string Resolve(string root, string relative)
    {
        var full = System.IO.Path.GetFullPath(System.IO.Path.Combine(root, relative.Replace('/', System.IO.Path.DirectorySeparatorChar)));
        var rootFull = System.IO.Path.GetFullPath(root).TrimEnd(System.IO.Path.DirectorySeparatorChar) + System.IO.Path.DirectorySeparatorChar;
        if (!full.StartsWith(rootFull, StringComparison.OrdinalIgnoreCase)) throw new DeltaUnavailableException($"{relative} leaves the folder");
        return full;
    }

    private static bool IsHex(string s)
    {
        foreach (var c in s)
            if (!Uri.IsHexDigit(c)) return false;
        return true;
    }

    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNameCaseInsensitive = false };

    // The wire shape (camelCase, as zip_manifest.py writes it).
    private sealed class Raw
    {
        [System.Text.Json.Serialization.JsonPropertyName("format")] public int Format { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("zip")] public string? Zip { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("zipSize")] public long ZipSize { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("zipSha256")] public string? ZipSha256 { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("files")] public List<RawFile?>? Files { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("dirs")] public List<string?>? Dirs { get; set; }
    }

    private sealed class RawFile
    {
        [System.Text.Json.Serialization.JsonPropertyName("path")] public string? Path { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("size")] public long Size { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("sha256")] public string? Sha256 { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("offset")] public long Offset { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("csize")] public long Csize { get; set; }
        [System.Text.Json.Serialization.JsonPropertyName("method")] public int Method { get; set; }
    }
}

/// <summary>
/// The changed-files-only path can't be used for this update (no list, a list that doesn't fit, a server that ignores
/// Range, a file that didn't verify, ...). UpdateService catches it and downloads the whole zip instead; the player
/// never sees it.
/// </summary>
public sealed class DeltaUnavailableException(string message, Exception? inner = null) : Exception(message, inner);
