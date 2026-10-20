using System.IO;
using System.IO.Compression;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Mods.Icons;

/// <summary>
/// Reads the icon a mod ships inside its own jar: fabric.mod.json "icon" (a path string, or a
/// size map like {"128x128": "a.png", "32x32": "b.png"} where the largest wins), quilt.mod.json
/// (quilt_loader.metadata.icon, same two forms) and mods.toml / neoforge.mods.toml "logoFile".
/// Offline and instant. Nothing here throws: any unreadable jar or entry is simply "no icon".
/// No WPF types, so the unit tests link this file as is.
/// </summary>
public static class JarIconReader
{
    /// <summary>An icon bigger than this inside a jar is ignored (a decompression-bomb guard).</summary>
    public const long MaxIconBytes = 1024 * 1024;

    private const long MaxManifestBytes = 512 * 1024;

    private static readonly Regex LogoFile = new(
        @"^\s*logoFile\s*=\s*""(?<p>[^""]+)""", RegexOptions.Multiline | RegexOptions.CultureInvariant);

    /// <summary>The icon path from a fabric.mod.json / quilt.mod.json document, or null.</summary>
    public static string? ParseIconPath(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json, new JsonDocumentOptions
            {
                AllowTrailingCommas = true,
                CommentHandling = JsonCommentHandling.Skip,
            });
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;

            if (root.TryGetProperty("icon", out var icon))
                return Normalize(FromIconElement(icon));

            // Quilt keeps it under quilt_loader.metadata.
            if (root.TryGetProperty("quilt_loader", out var ql) && ql.ValueKind == JsonValueKind.Object
                && ql.TryGetProperty("metadata", out var md) && md.ValueKind == JsonValueKind.Object
                && md.TryGetProperty("icon", out var qicon))
                return Normalize(FromIconElement(qicon));
        }
        catch (JsonException) { }
        return null;
    }

    /// <summary>The logoFile path from a mods.toml document, or null.</summary>
    public static string? ParseLogoFile(string toml)
    {
        var m = LogoFile.Match(toml);
        return m.Success ? Normalize(m.Groups["p"].Value) : null;
    }

    // A string, or an object of "<size>" (or "<w>x<h>") -> path: the largest size wins.
    private static string? FromIconElement(JsonElement icon)
    {
        if (icon.ValueKind == JsonValueKind.String) return icon.GetString();
        if (icon.ValueKind != JsonValueKind.Object) return null;

        string? best = null;
        var bestSize = -1;
        foreach (var p in icon.EnumerateObject())
        {
            if (p.Value.ValueKind != JsonValueKind.String) continue;
            var size = SizeOf(p.Name);
            if (size > bestSize)
            {
                bestSize = size;
                best = p.Value.GetString();
            }
        }
        return best;
    }

    private static int SizeOf(string key)
    {
        var digits = key.Split('x', 'X')[0];
        return int.TryParse(digits, out var n) && n > 0 ? n : 0;
    }

    private static string? Normalize(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return null;
        var p = path.Trim().Replace('\\', '/').TrimStart('/');
        // A jar entry name never legitimately climbs out of the archive.
        return p.Length == 0 || p.Contains("../", StringComparison.Ordinal) ? null : p;
    }

    /// <summary>The icon's raw bytes from inside the jar, or null when it has none or cannot be read.</summary>
    public static byte[]? ReadIcon(string jarPath)
    {
        try
        {
            // The game may have the jar open: share every way.
            using var fs = new FileStream(jarPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var zip = new ZipArchive(fs, ZipArchiveMode.Read);

            foreach (var name in new[] { "fabric.mod.json", "quilt.mod.json" })
            {
                var text = ReadText(zip, name);
                if (text == null) continue;
                var path = ParseIconPath(text);
                var bytes = path == null ? null : ReadBytes(zip, path);
                if (bytes != null) return bytes;
            }
            foreach (var name in new[] { "META-INF/mods.toml", "META-INF/neoforge.mods.toml" })
            {
                var text = ReadText(zip, name);
                if (text == null) continue;
                var path = ParseLogoFile(text);
                var bytes = path == null ? null : ReadBytes(zip, path);
                if (bytes != null) return bytes;
            }
        }
        catch
        {
            // Corrupt zip, locked file, anything: no icon, never an error.
        }
        return null;
    }

    private static string? ReadText(ZipArchive zip, string name)
    {
        var entry = zip.GetEntry(name);
        if (entry == null || entry.Length > MaxManifestBytes) return null;
        using var reader = new StreamReader(entry.Open());
        return reader.ReadToEnd();
    }

    private static byte[]? ReadBytes(ZipArchive zip, string name)
    {
        var entry = zip.GetEntry(name);
        if (entry == null || entry.Length == 0 || entry.Length > MaxIconBytes) return null;
        using var s = entry.Open();
        using var ms = new MemoryStream((int)entry.Length);
        s.CopyTo(ms);
        return ms.ToArray();
    }
}
