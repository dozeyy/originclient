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
/// One small marker file per version in the launcher's own state folder (OriginPaths.InstallState), NOT in
/// the version's game folder: InstanceMigration treats every unknown top-level name in a version folder as
/// old-layout account data and moves it into accounts/&lt;key&gt;/, which would carry the marker away and make
/// a half-installed version read Installed. The folder comes from the caller (a temp folder in tests).
/// Never throws: a marker that can't be written or removed only means this protection is off for that
/// launch, and that is logged in one line.
/// </summary>
public sealed class InstallJournal
{
    /// <summary>The marker's file name inside the version's root.</summary>
    public const string MarkerFileName = "origin-installing.json";

    private readonly Func<string, string> _folder;
    private readonly Action<string>? _log;

    /// <param name="markerFolder">The folder that holds a version's marker.</param>
    /// <param name="log">One line per swallowed write or delete failure (the launcher log).</param>
    public InstallJournal(Func<string, string> markerFolder, Action<string>? log = null)
    {
        _folder = markerFolder;
        _log = log;
    }

    /// <summary>The launcher's journal over the real data folder.</summary>
    public static InstallJournal Shared { get; } = new(OriginPaths.InstallState, Import.Launcher.ImportLog.Write);

    private string MarkerOf(string version) => Path.Combine(_folder(version), MarkerFileName);

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
        catch (Exception ex)
        {
            // Protection off for this launch; Play still works.
            Say($"install journal: could not write the marker for {version} ({ex.GetType().Name})");
        }
    }

    /// <summary>Marks the install as finished (provisioning came back clean).</summary>
    public void Close(string version)
    {
        try { File.Delete(MarkerOf(version)); }
        catch (Exception ex)
        {
            // The version reads Partial until the next clean Play removes it.
            Say($"install journal: could not remove the marker for {version} ({ex.GetType().Name})");
        }
    }

    private void Say(string line)
    {
        try { _log?.Invoke(line); }
        catch { /* a courtesy */ }
    }
}
