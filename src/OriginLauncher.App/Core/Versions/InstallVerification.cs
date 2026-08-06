using System.IO;
using System.Text.Json;
using OriginLauncher.App.Core.Updates;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// Per-instance record that provisioning already completed successfully, so a
/// later launch can skip the work that only ever re-derives the same answer.
///
/// It covers two costs, because both are "prove again what we proved last time":
///
/// 1. INTEGRITY. CmlLib defaults to CheckFileChecksum = true / CheckFileSize =
///    false, so every launch re-hashed the entire install — all assets, every
///    library, the client jar, and the whole Mojang Java runtime, roughly
///    0.5-1.5 GB of disk reads, at no more than 4 hashing threads. In the steady
///    state that produces zero downloads; it only makes the player wait.
///
/// 2. LOADER RESOLUTION. The Fabric installer ran unconditionally on every
///    launch: two round-trips to meta.fabricmc.net plus a rewrite of the version
///    JSON, to arrive at the same loader that is already installed on disk.
///
/// THE SAFETY CONTRACT (deliberately not "trust the disk forever"):
///   - The first launch of an instance does the full pass exactly as before.
///   - The stamp key includes the launcher build, so a launcher update — which
///     is what ships new bundled jars and new pins — always re-verifies and
///     re-resolves the loader.
///   - The fast path turns size checking ON, which CmlLib had OFF, so it is
///     strictly stronger than a bare existence check: truncated or
///     partially-written files are still caught.
///   - The cached loader name is only trusted while its version folder actually
///     exists on disk; otherwise the stamp is ignored and Fabric re-resolves.
///   - A crashed or failed boot calls Invalidate(), so the next launch re-does
///     everything. A corrupt file costs one bad launch, not a dead instance.
/// </summary>
public static class InstallVerification
{
    // Bumped only if the stamp's meaning changes; an unknown schema reads as
    // "no stamp", which fails safe to the full path.
    private const int CurrentSchema = 2;

    private const string StampFileName = "origin-verified.json";

    private sealed class Stamp
    {
        public int Schema { get; set; }
        public string? MinecraftVersion { get; set; }
        public string? ResolvedVersionName { get; set; }
        public string? Launcher { get; set; }
        public DateTimeOffset VerifiedUtc { get; set; }
    }

    /// <summary>
    /// What a valid stamp lets this launch skip. ResolvedVersionName is null
    /// when the loader still has to be resolved from the network.
    /// </summary>
    public readonly record struct Decision(bool FullVerify, string? CachedVersionName)
    {
        public static Decision Full => new(true, null);
    }

    private static string StampPath(string instanceBasePath) =>
        Path.Combine(instanceBasePath, StampFileName);

    /// <summary>
    /// Decides whether this launch can take the fast path. Any doubt — missing,
    /// unreadable, or mismatched stamp, or a cached loader whose version folder
    /// has since gone — returns Full, because a needless verify only costs time
    /// while a wrongly-skipped one can ship a broken install to the player.
    /// </summary>
    public static Decision Evaluate(string instanceBasePath, string minecraftVersion)
    {
        try
        {
            var file = StampPath(instanceBasePath);
            if (!File.Exists(file)) return Decision.Full;

            var stamp = JsonSerializer.Deserialize<Stamp>(File.ReadAllText(file));
            if (stamp is null) return Decision.Full;
            if (stamp.Schema != CurrentSchema) return Decision.Full;
            if (!string.Equals(stamp.MinecraftVersion, minecraftVersion, StringComparison.Ordinal))
                return Decision.Full;
            if (!string.Equals(stamp.Launcher, LauncherStampValue, StringComparison.Ordinal))
                return Decision.Full;

            // The cached loader name is only usable if its installed version
            // folder is still there — a hand-deleted or half-cleaned instance
            // must fall back to a real Fabric install.
            var cachedName = stamp.ResolvedVersionName;
            if (string.IsNullOrWhiteSpace(cachedName)) return Decision.Full;

            var versionJson = Path.Combine(instanceBasePath, "versions", cachedName, cachedName + ".json");
            if (!File.Exists(versionJson)) return Decision.Full;

            return new Decision(FullVerify: false, CachedVersionName: cachedName);
        }
        catch
        {
            return Decision.Full;
        }
    }

    /// <summary>
    /// Called once provisioning has completed without error. Best-effort: a
    /// stamp that cannot be written just means the next launch repeats the work.
    /// </summary>
    public static void MarkVerified(string instanceBasePath, string minecraftVersion, string resolvedVersionName)
    {
        try
        {
            Directory.CreateDirectory(instanceBasePath);
            var stamp = new Stamp
            {
                Schema = CurrentSchema,
                MinecraftVersion = minecraftVersion,
                ResolvedVersionName = resolvedVersionName,
                Launcher = LauncherStampValue,
                VerifiedUtc = DateTimeOffset.UtcNow
            };
            File.WriteAllText(StampPath(instanceBasePath),
                JsonSerializer.Serialize(stamp, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
            // Non-fatal: no stamp simply means "do it properly next time".
        }
    }

    /// <summary>
    /// Drops the stamp so the next launch re-verifies and re-resolves in full.
    /// Called when a launch crashes or dies during boot — the one situation
    /// where a bad file on disk is a live suspect.
    /// </summary>
    public static void Invalidate(string instanceBasePath)
    {
        try
        {
            var file = StampPath(instanceBasePath);
            if (File.Exists(file)) File.Delete(file);
        }
        catch
        {
            // Best-effort; a surviving stamp only means no re-verify.
        }
    }

    // Dev builds share one sentinel so local rebuilds don't force a re-verify
    // every run; real releases key on the actual version.
    private static string LauncherStampValue =>
        UpdateService.IsDevBuild ? "dev" : UpdateService.CurrentVersion.ToString();
}
