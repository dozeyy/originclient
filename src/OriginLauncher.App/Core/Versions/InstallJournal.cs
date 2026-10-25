using System.IO;
using System.Text.Json;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// "An install of this version started and has not finished" (Phase 3, U3.3). Play opens it before it
/// downloads or repairs a version and closes it only after provisioning came back clean. While it is open
/// InstalledVersions reports the version as Partial, never Installed, so a failed, cancelled or crashed
/// install can never be shown (or trusted) as a finished one. Nothing is deleted: the files stay, the next
/// Play picks them up and finishes.
///
/// One small marker file in the version's shared root (next to InstallVerification's stamp), whose folder
/// comes from the caller (OriginPaths.VersionRoot in the launcher, a temp folder in tests).
/// Never throws: a marker that can't be written or removed only means this protection is off for that launch.
/// </summary>
public sealed class InstallJournal
{
    /// <summary>The marker's file name inside the version's root.</summary>
    public const string MarkerFileName = "origin-installing.json";

    private readonly Func<string, string> _versionRoot;

    public InstallJournal(Func<string, string> versionRoot) => _versionRoot = versionRoot;

    /// <summary>The launcher's journal over the real data folder.</summary>
    public static InstallJournal Shared { get; } = new(OriginPaths.VersionRoot);

    private string MarkerOf(string version) => Path.Combine(_versionRoot(version), MarkerFileName);

    /// <summary>True while an install of the version started and did not finish.</summary>
    public bool IsOpen(string version)
    {
        try { return File.Exists(MarkerOf(version)); }
        catch { return false; }
    }

    /// <summary>Marks an install as started. Idempotent.</summary>
    public void Open(string version)
    {
        try
        {
            var marker = MarkerOf(version);
            Directory.CreateDirectory(Path.GetDirectoryName(marker)!);
            AtomicFile.WriteAllText(marker, JsonSerializer.Serialize(new { version, startedUtc = DateTime.UtcNow }));
        }
        catch { /* protection off for this launch; Play still works */ }
    }

    /// <summary>Marks the install as finished (provisioning came back clean).</summary>
    public void Close(string version)
    {
        try { File.Delete(MarkerOf(version)); }
        catch { /* the version reads Partial until the next clean Play removes it */ }
    }
}
