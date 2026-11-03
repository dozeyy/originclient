using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace OriginLauncher.App.Core.Updates;

// The small self-update ("delta"): rebuild the new release from the installed launcher folder plus only the files
// that changed, instead of downloading the whole ~250 MB zip. Built per release by tools/release/delta.py, published
// as <zip prefix>.delta-from-<installed version>.zip with its own .sha256 (docs/RELEASING.md -> Small updates).
// Pure (paths are parameters; no network, no OriginPaths, no UI) so the tests drive the real code on temp folders.
//
// Why it can never leave a broken install:
//  - The delta is downloaded and checked against its published SHA-256 exactly like the full zip (UpdateService),
//    so its manifest is as trusted as the full zip itself.
//  - Stage() writes the new release into an EMPTY stage folder (the same folder the full zip would be unpacked
//    into), file by file from the manifest: a changed file from the delta, an unchanged one copied from the
//    installed folder. Nothing in the installed folder is touched.
//  - VerifyStaged() then reads every staged file back and checks its size and SHA-256 against the manifest, and
//    that the stage holds nothing else. A file that drifted locally (antivirus, a hand edit, a half-finished
//    earlier copy) or a delta made for another version fails here, before any swap is started.
//  - Any failure means "use the full zip instead": TryStage answers false and leaves the stage empty, and
//    UpdateService.TryStageFromDeltaAsync downloads the full zip as before. The swap script itself is unchanged and
//    sees the same stage folder the full zip would have produced.
// What it deliberately does NOT do: delete files the new release dropped. The full-zip swap (robocopy without
// /PURGE) never has either, so both paths leave the install folder identical. The manifest's "deleted" list is
// for people reading it.

/// <summary>The delta could not be used (bad manifest, a file that doesn't match). The caller falls back to the full zip.</summary>
public sealed class UpdateDeltaException(string message) : IOException(message);

public static class UpdateDelta
{
    /// <summary>The manifest's entry name inside the delta zip (tools/release/delta.py MANIFEST).</summary>
    public const string ManifestEntry = "origin-delta.json";
    /// <summary>Changed and added files sit under this folder inside the delta zip (delta.py FILES_PREFIX).</summary>
    public const string FilesPrefix = "files/";
    private const int SupportedFormat = 1;
    // The real manifest is ~3 KB (29 files); anything near this is not ours.
    private const long MaxManifestBytes = 4 << 20;

    public sealed record ManifestFile(
        [property: JsonPropertyName("path")] string Path,
        [property: JsonPropertyName("size")] long Size,
        [property: JsonPropertyName("sha256")] string Sha256);

    private sealed record Manifest(
        [property: JsonPropertyName("format")] int Format,
        [property: JsonPropertyName("from")] string? From,
        [property: JsonPropertyName("to")] string? To,
        [property: JsonPropertyName("files")] List<ManifestFile>? Files);

    /// <summary>
    /// True when <paramref name="assetName"/> is the delta that upgrades exactly <paramref name="installed"/>:
    /// "<paramref name="zipPrefix"/>.delta-from-1.0.84.zip" for an installed 1.0.84 (assembly version 1.0.84.0).
    /// </summary>
    public static bool IsDeltaFor(string? assetName, string zipPrefix, Version installed)
    {
        if (assetName == null) return false;
        var head = zipPrefix + ".delta-from-";
        if (!assetName.StartsWith(head, StringComparison.OrdinalIgnoreCase)
            || !assetName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) return false;
        return Version.TryParse(assetName[head.Length..^".zip".Length], out var from)
               && UpdateGate.Normalize(from) == UpdateGate.Normalize(installed);
    }

    /// <summary>
    /// The one entry point UpdateService uses. True: <paramref name="stageDir"/> holds the new release, every file
    /// verified. False: the delta can't be used (<paramref name="failure"/> says why for the log) and the stage is left
    /// empty for the full zip. Never throws, and never touches <paramref name="installedDir"/>.
    /// </summary>
    public static bool TryStage(string deltaZip, string installedDir, string stageDir, Version from, Version to, out string failure)
    {
        // Only ever an empty stage: the cleanup below must never delete anything this delta didn't write.
        if (Directory.Exists(stageDir) && Directory.EnumerateFileSystemEntries(stageDir).Any())
        {
            failure = "the stage folder is not empty";
            return false;
        }
        try
        {
            Stage(deltaZip, installedDir, stageDir, from, to);
            failure = "";
            return true;
        }
        catch (Exception ex)
        {
            failure = $"{ex.GetType().Name}: {ex.Message}";
            try
            {
                if (Directory.Exists(stageDir)) Directory.Delete(stageDir, recursive: true);
                Directory.CreateDirectory(stageDir);
            }
            catch (Exception cleanup) when (cleanup is IOException or UnauthorizedAccessException)
            {
                // The full zip's extraction overwrites whatever is left: every staged file is a file of the new release.
                failure += $" (the stage could not be emptied: {cleanup.Message})";
            }
            return false;
        }
    }

    /// <summary>
    /// Writes the release the delta describes into <paramref name="stageDir"/> and verifies every staged file. Throws on
    /// anything unexpected. Called through <see cref="TryStage"/>, which turns a throw into "use the full zip".
    /// </summary>
    private static void Stage(string deltaZip, string installedDir, string stageDir, Version from, Version to)
    {
        Directory.CreateDirectory(stageDir);

        using var zip = ZipFile.OpenRead(deltaZip);
        var files = ReadManifest(zip, from, to);
        var payload = PayloadEntries(zip, files);

        foreach (var file in files)
        {
            var dest = StagedPath(stageDir, file.Path);
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            if (payload.TryGetValue(file.Path, out var entry))
            {
                entry.ExtractToFile(dest, overwrite: false);
            }
            else
            {
                // Unchanged since the installed version: copied from the installed folder. A missing file is the
                // same failure as a changed one (VerifyStaged would catch a wrong one either way).
                var installed = Path.Combine(installedDir, file.Path.Replace('/', Path.DirectorySeparatorChar));
                if (!File.Exists(installed))
                    throw new UpdateDeltaException($"{file.Path} is missing from the installed launcher");
                // The running exe is in this folder: open it the way Windows lets a running image be read.
                using var src = new FileStream(installed, FileMode.Open, FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete, 1 << 16);
                using var dst = new FileStream(dest, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1 << 16);
                src.CopyTo(dst, 1 << 20);
            }
        }

        VerifyStaged(stageDir, files);
    }

    /// <summary>
    /// The check before the swap: every manifest file is in <paramref name="stageDir"/> with exactly its size and
    /// SHA-256, and the stage holds no other file. Reads the files back from disk, so it also catches anything that
    /// changed them after they were written.
    /// </summary>
    public static void VerifyStaged(string stageDir, IReadOnlyList<ManifestFile> files)
    {
        foreach (var file in files)
        {
            var path = StagedPath(stageDir, file.Path);
            var info = new FileInfo(path);
            if (!info.Exists) throw new UpdateDeltaException($"{file.Path} was not staged");
            if (info.Length != file.Size) throw new UpdateDeltaException($"{file.Path} has the wrong size");
            string actual;
            using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16))
                actual = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
            if (actual != file.Sha256) throw new UpdateDeltaException($"{file.Path} does not match the update's manifest");
        }
        var staged = Directory.EnumerateFiles(stageDir, "*", SearchOption.AllDirectories).Count();
        if (staged != files.Count)
            throw new UpdateDeltaException($"the stage holds {staged} files, the manifest {files.Count}");
    }

    /// <summary>
    /// Reads and checks the manifest: format 1, made for exactly this <paramref name="from"/> and <paramref name="to"/>
    /// (a delta attached to the wrong release would otherwise install an older version as if it were the newer one),
    /// at least one file, every path a safe relative path, no duplicates (case-insensitive, as on Windows), every hash
    /// 64 lower-case hex digits.
    /// </summary>
    public static IReadOnlyList<ManifestFile> ReadManifest(ZipArchive zip, Version from, Version to)
    {
        var entry = zip.GetEntry(ManifestEntry) ?? throw new UpdateDeltaException("the update has no manifest");
        if (entry.Length > MaxManifestBytes) throw new UpdateDeltaException("the update's manifest is too big");
        Manifest? manifest;
        try
        {
            using var stream = entry.Open();
            manifest = JsonSerializer.Deserialize<Manifest>(stream);
        }
        catch (JsonException)
        {
            throw new UpdateDeltaException("the update's manifest could not be read");
        }
        if (manifest == null || manifest.Format != SupportedFormat)
            throw new UpdateDeltaException("the update's manifest has an unknown format");
        if (!SameVersion(manifest.From, from) || !SameVersion(manifest.To, to))
            throw new UpdateDeltaException($"the update was made for {manifest.From} -> {manifest.To}, not {from} -> {to}");
        if (manifest.Files is not { Count: > 0 } files)
            throw new UpdateDeltaException("the update's manifest lists no files");

        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var f in files)
        {
            if (f == null || !IsSafeRelativePath(f.Path) || !seen.Add(f.Path))
                throw new UpdateDeltaException("the update's manifest has a bad or repeated path");
            if (f.Size < 0 || f.Sha256 is not { Length: 64 } h || !h.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f'))
                throw new UpdateDeltaException($"the update's manifest has a bad entry for {f.Path}");
        }
        return files;
    }

    // Every zip entry besides the manifest must be files/<a manifest path>, each at most once. Anything else means the
    // delta is not what delta.py builds, so it is not used at all.
    private static Dictionary<string, ZipArchiveEntry> PayloadEntries(ZipArchive zip, IReadOnlyList<ManifestFile> files)
    {
        var wanted = new HashSet<string>(files.Select(f => f.Path), StringComparer.OrdinalIgnoreCase);
        var payload = new Dictionary<string, ZipArchiveEntry>(StringComparer.OrdinalIgnoreCase);
        foreach (var entry in zip.Entries)
        {
            if (entry.FullName == ManifestEntry) continue;
            if (!entry.FullName.StartsWith(FilesPrefix, StringComparison.Ordinal))
                throw new UpdateDeltaException($"the update holds an unexpected entry {entry.FullName}");
            var path = entry.FullName[FilesPrefix.Length..];
            if (!wanted.Contains(path) || !payload.TryAdd(path, entry))
                throw new UpdateDeltaException($"the update holds a file its manifest doesn't list: {path}");
        }
        return payload;
    }

    private static bool SameVersion(string? text, Version expected) =>
        Version.TryParse(text, out var v) && UpdateGate.Normalize(v) == UpdateGate.Normalize(expected);

    /// <summary>A relative '/'-path with no empty, "." or ".." part, no '\' and no ':' (drive or NTFS stream).</summary>
    public static bool IsSafeRelativePath(string? path)
    {
        if (string.IsNullOrEmpty(path) || path[0] == '/' || path.Contains('\\') || path.Contains(':')
            || path.Any(char.IsControl)) return false;
        foreach (var part in path.Split('/'))
            if (part is "" or "." or "..") return false;
        return true;
    }

    // The staged file for a manifest path, refused if it would land outside the stage (IsSafeRelativePath already
    // rules that out; this is the belt to that brace, on the real resolved path).
    private static string StagedPath(string stageDir, string relative)
    {
        var root = Path.GetFullPath(stageDir).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var full = Path.GetFullPath(Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar)));
        if (!full.StartsWith(root, StringComparison.OrdinalIgnoreCase))
            throw new UpdateDeltaException($"{relative} would be written outside the update folder");
        return full;
    }
}
