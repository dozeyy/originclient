using System.IO;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Sync;

// servers.dat, the multiplayer server list: synced as one value (key ""),
// newest wins. It is uncompressed NBT that every version from 1.16.5 to
// 1.21.11 reads (unknown tags are ignored), so the bytes are copied as-is.
// The store keeps the bytes (base64); baselines keep only a SHA-256.
public sealed class WholeFileFormat : ISyncFormat
{
    public static readonly WholeFileFormat ServerList = new();

    // A real server list is a few KB (server icons included); anything huge is
    // not something to copy into every instance.
    private const int MaxBytes = 4 * 1024 * 1024;
    private const byte NbtCompoundTag = 0x0A;

    public bool AllowsDeletions => false;

    public Dictionary<string, string>? Read(string path)
    {
        var bytes = ReadValid(path);
        return bytes == null ? null : new Dictionary<string, string> { [""] = Convert.ToBase64String(bytes) };
    }

    public bool Rejects(string key, string raw) => false;

    public bool Equivalent(string key, string a, string b) => string.Equals(a, b, StringComparison.Ordinal);

    public string BaselineOf(string raw) => "sha256:" + Convert.ToHexString(SHA256.HashData(Convert.FromBase64String(raw)));

    public bool MatchesBaseline(string key, string raw, string baseline) => BaselineOf(raw) == baseline;

    public Dictionary<string, string>? Apply(string path, IReadOnlyDictionary<string, string?> desired, SyncInstance target)
    {
        if (!desired.TryGetValue("", out var raw) || raw == null) return Read(path);
        byte[] bytes;
        try { bytes = Convert.FromBase64String(raw); }
        catch (FormatException) { return null; }
        if (!LooksValid(bytes)) return null;

        if (File.Exists(path))
        {
            var current = ReadValid(path);
            if (current == null) return null; // unreadable/odd: leave it alone
            if (current.AsSpan().SequenceEqual(bytes))
                return new Dictionary<string, string> { [""] = raw };
        }

        AtomicFile.WriteAllBytes(path, bytes);
        return new Dictionary<string, string> { [""] = raw };
    }

    private static byte[]? ReadValid(string path)
    {
        try
        {
            if (new FileInfo(path).Length > MaxBytes) return null;
            var bytes = File.ReadAllBytes(path);
            return LooksValid(bytes) ? bytes : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    // Uncompressed NBT: a root compound tag, then its name length (two bytes).
    private static bool LooksValid(byte[] bytes) =>
        bytes.Length >= 3 && bytes.Length <= MaxBytes && bytes[0] == NbtCompoundTag;
}
