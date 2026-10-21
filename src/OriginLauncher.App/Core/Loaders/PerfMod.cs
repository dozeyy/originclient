using System.IO;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Loaders;

// A single performance-mod jar pinned to an exact Modrinth version + direct
// download URL, captured from a live API snapshot rather than "latest" — see
// PerformanceModCatalog.Data.cs for why exact pins matter (Indium in
// particular breaks silently if paired with the wrong Sodium build).
//
// Sha512 is the hash Modrinth itself publishes for that exact file
// (files[].hashes.sha512 — Modrinth does not publish SHA-256). Every catalog
// pin carries one (PerformanceModCatalogTests enforces it) and the installer
// refuses a download that doesn't match, so a truncated or tampered jar is never
// dropped into a mods folder. Nullable only so a hand-written pin in a test or
// a future one-off still compiles; the catalog test is what keeps the data honest.
public sealed record PerfMod(string Version, string Url, string FileName, string? Sha512 = null)
{
    // True when no hash is pinned (nothing to check) or the file's SHA-512 matches.
    public bool Matches(string path)
    {
        if (string.IsNullOrEmpty(Sha512)) return true;
        using var stream = File.OpenRead(path);
        var actual = Convert.ToHexString(SHA512.HashData(stream));
        return actual.Equals(Sha512, StringComparison.OrdinalIgnoreCase);
    }
}
