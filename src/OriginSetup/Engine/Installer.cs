using System.Diagnostics;
using System.Net.Http;
using System.Threading;
using OriginLauncher.App.Core.Install;

namespace OriginSetup.Engine;

/// <summary>What the player chose. Null RamMb/DefaultVersion = leave the launcher's own default.</summary>
public sealed class InstallOptions
{
    public string Directory { get; set; } = InstallLocation.Default;
    /// <summary>
    /// Start with Windows on/off; null = keep what is set now (a silent install without
    /// --start-with-windows / --no-start-with-windows must not switch off the player's choice).
    /// </summary>
    public bool? StartWithWindows { get; set; }
    public int? RamMb { get; set; }
    public string? DefaultVersion { get; set; }
    /// <summary>Settings Sync turned on/off on the Customize page; null = the player left it as it was.</summary>
    public bool? SettingsSync { get; set; }
    /// <summary>Install from this launcher zip instead of downloading (CI's smoke test: the zip it just built).</summary>
    public string? SourceZip { get; set; }
}

public enum InstallStage { Preparing, Downloading, Unpacking, Finishing, Done }

/// <summary>One progress tick. Bytes are the download's while Downloading.</summary>
public sealed class InstallProgress
{
    public InstallProgress(InstallStage stage, double fraction, long bytes = 0, long? totalBytes = null, string? version = null)
    {
        Stage = stage;
        Fraction = fraction;
        Bytes = bytes;
        TotalBytes = totalBytes;
        Version = version;
    }

    public InstallStage Stage { get; }
    /// <summary>Whole install, 0..1.</summary>
    public double Fraction { get; }
    public long Bytes { get; }
    public long? TotalBytes { get; }
    /// <summary>The launcher version being installed, once known.</summary>
    public string? Version { get; }
}

/// <summary>A problem the player can act on, worded for them.</summary>
public sealed class SetupException : Exception
{
    public SetupException(string message, Exception? inner = null) : base(message, inner) { }
}

// Installs (or updates/repairs) Origin: downloads the newest released
// launcher (OriginLauncher-win-x64.zip, see ReleaseFeed) and unpacks it.
//
// Safe by construction:
//  - The download and the unpack both happen in a staging folder INSIDE the
//    install folder; only a complete, verified unpack (size + CRC-32 of every
//    file, SHA-256 of the download when the feed publishes one) is moved into
//    place. Cancelling, a dropped connection or a damaged download leaves an
//    existing install exactly as it was.
//  - After the move begins there is no cancel — the rest takes a second. Every
//    file it replaces is set aside first, so a file that can't be written (the
//    launcher started meanwhile, antivirus holding a jar) puts the old install
//    back whole instead of leaving half of each.
//  - origin-install.json records what was put where (InstallManifest), the
//    only thing uninstall trusts.
//  - Taking over an install made by the old Inno Setup installer replaces its
//    Add/Remove Programs entry (same key) and removes its uninstaller files.
public static class Installer
{
    public const string StagingFolder = "_setup-staging";

    /// <summary>
    /// Disk space to ask for before the real size is known: the download
    /// (~130 MB) plus the unpacked launcher (~230 MB), with headroom. Checked
    /// again with exact numbers once the download is in.
    /// </summary>
    public const long SpaceEstimate = 400L * 1024 * 1024;

    // How the progress bar is shared out between the stages.
    private const double DownloadStart = 0.02, DownloadEnd = 0.80, UnpackEnd = 0.95;

    /// <param name="release">The release the Welcome page showed; null = look it up now.</param>
    /// <param name="http">Tests pass a client with a fake handler.</param>
    /// <returns>Notes for the Done page: the finishing touches Windows refused (empty when everything worked).</returns>
    public static IReadOnlyList<string> Run(string setupExePath, InstallOptions options, LauncherRelease? release,
                           Action<InstallProgress> report, CancellationToken ct, HttpClient? http = null)
    {
        var dir = options.Directory;
        // Before anything below creates it: a folder Setup makes is Origin's alone (InstallManifest.OwnsFolder).
        bool createdFolder = !Directory.Exists(dir);
        // Before the commit puts the new launcher in place: an update or repair over a working install.
        bool updating = File.Exists(Path.Combine(dir, InstallLocation.LauncherExe));
        SetupLog.Write($"install -> {dir} (source={options.SourceZip ?? "download"}, startup={options.StartWithWindows?.ToString() ?? "unchanged"}, ram={options.RamMb?.ToString() ?? "default"}, version={options.DefaultVersion ?? "default"}, settingsSync={options.SettingsSync?.ToString() ?? "unchanged"})");
        report(new InstallProgress(InstallStage.Preparing, 0, version: release?.Version));

        var problem = InstallLocation.Problem(dir, InstallLocation.ProtectedRoots())
                      ?? InstallLocation.ProbeWritable(dir)
                      ?? InstallLocation.SpaceProblem(dir, SpaceEstimate);
        if (problem != null) throw new SetupException(problem);
        if (LauncherProcess.IsRunning())
            throw new SetupException("Origin Launcher is still open. Close it and try again.");

        var staged = Stage(dir, options.SourceZip, release, report, ct, http, setupExePath);
        var version = staged.Version;

        // Point of no return.
        report(new InstallProgress(InstallStage.Finishing, UnpackEnd, version: version));
        Commit(staged, dir, createdFolder);

        report(new InstallProgress(InstallStage.Finishing, 0.97, version: version));
        var exe = Path.Combine(dir, InstallLocation.LauncherExe);
        Shortcuts.DeleteLegacyGroup();
        const string description = "Origin - the Minecraft launcher for every version";
        // The launcher is in place now, so nothing below may turn this into "Origin wasn't installed". The
        // Add/Remove Programs entry goes first (it is what lets the player uninstall); a desktop the system protects
        // (Defender's Controlled folder access, a OneDrive Desktop) refusing the shortcut is just a note.
        var steps = new List<FinishStep>
        {
            new FinishStep("Add/Remove Programs entry", () => Registration.Write(dir, version),
                "Windows didn't let Setup add Origin to Installed apps. Run Setup again later to add it."),
            new FinishStep("Start menu shortcut", () => Shortcuts.Create(Shortcuts.StartMenuPath, exe, description),
                "Couldn't add Origin to the Start menu."),
        };
        if (Shortcuts.WantsDesktop(updating, File.Exists(Shortcuts.DesktopPath)))
            steps.Add(new FinishStep("desktop shortcut", () => Shortcuts.Create(Shortcuts.DesktopPath, exe, description),
                "Couldn't add a desktop shortcut: Windows protects your desktop from new files."));
        else
            SetupLog.Write("desktop shortcut: left off (the player removed it before this update)");
        steps.Add(new FinishStep("Start with Windows", () => InstallRegistry.SetStartWithWindows(options.StartWithWindows ?? StartsWithWindowsNow(), exe),
            "Couldn't change Start with Windows. You can set it in Origin's Settings."));
        steps.Add(new FinishStep("Customize choices", () => Handoff.Write(options.RamMb, options.DefaultVersion, options.SettingsSync),
            "Your Customize choices weren't saved. You can set them in Origin's Settings."));
        var notes = Finish(steps);

        SetupLog.Write($"install complete ({version}){(notes.Count > 0 ? $", {notes.Count} finishing step(s) refused" : "")}");
        report(new InstallProgress(InstallStage.Done, 1, version: version));
        return notes;
    }

    /// <summary>One finishing touch after the launcher is in place, and the player's note if Windows refuses it.</summary>
    public sealed class FinishStep
    {
        public FinishStep(string what, Action run, string note)
        {
            What = what;
            Run = run;
            Note = note;
        }

        public string What { get; }
        public Action Run { get; }
        public string Note { get; }
    }

    /// <summary>
    /// Runs every step, in order, whatever the ones before it did; a step that throws is logged and becomes its
    /// note. Before 2026-11-04 the first refusal (a desktop shortcut blocked by Controlled folder access) skipped
    /// every later step and showed "Origin wasn't installed" over a launcher that was already installed.
    /// </summary>
    public static List<string> Finish(IEnumerable<FinishStep> steps)
    {
        var notes = new List<string>();
        foreach (var step in steps)
        {
            try { step.Run(); }
            catch (Exception ex)
            {
                SetupLog.Write($"finish step refused ({step.What}): {ex.GetType().Name}: {ex.Message}");
                notes.Add(step.Note);
            }
        }
        return notes;
    }

    /// <summary>
    /// Moves a staged install into <paramref name="dir"/> and records it in origin-install.json.
    /// Checks the launcher is still closed first (the download took a minute, and an old desktop
    /// shortcut may have opened it). A file that can't be written puts back every file already
    /// replaced (FileOps.MoveTree) and throws: the old install stays whole.
    /// </summary>
    /// <param name="createdFolder">Setup created <paramref name="dir"/> for this install.</param>
    public static void Commit(StagedInstall staged, string dir, bool createdFolder)
    {
        var placed = Directory.EnumerateFiles(staged.FilesDir, "*", SearchOption.AllDirectories)
            .Select(f => f.Substring(staged.FilesDir.Length).TrimStart(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar))
            .ToList();
        var backup = Path.Combine(staged.StagingDir, "replaced");
        try
        {
            if (LauncherProcess.IsRunning())
                throw new SetupException("Origin Launcher is still open. Close it and try again.");
            FileOps.MoveTree(staged.FilesDir, dir, backup);
        }
        catch
        {
            // Staging is cleared only when every replaced file went back (an empty backup folder).
            bool restored = !Directory.Exists(backup) || !Directory.EnumerateFiles(backup, "*", SearchOption.AllDirectories).Any();
            if (restored)
            {
                FileOps.TryDeleteTree(staged.StagingDir);
                // A folder Setup made for this install holds only the empty subfolders the move created.
                if (createdFolder && Directory.Exists(dir) && !Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories).Any())
                    FileOps.TryDeleteTree(dir);
            }
            throw;
        }
        // The new launcher is in place: a record that can't be written must not undo the install. Without
        // it, uninstall falls back to the rules for installs older than the record (UninstallPlan).
        try { InstallManifest.Next(dir, placed, createdFolder).Write(dir); }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
        {
            SetupLog.Write($"could not write {InstallManifest.FileName}: {ex.Message}");
        }
        FileOps.TryDeleteTree(staged.StagingDir);
        foreach (var legacy in new[] { "unins000.exe", "unins000.dat", "unins000.msg" })
            FileOps.Retry(() => { var p = Path.Combine(dir, legacy); if (File.Exists(p)) File.Delete(p); });
    }

    // What the Run key says now; unreadable counts as off (the same answer Setup's own page shows).
    private static bool StartsWithWindowsNow()
    {
        try { return InstallRegistry.IsStartWithWindows(); }
        catch (Exception ex) when (ex is System.Security.SecurityException || ex is UnauthorizedAccessException) { return false; }
    }

    /// <summary>A verified launcher unpacked in the staging folder, ready to move into place.</summary>
    public sealed class StagedInstall
    {
        internal StagedInstall(string stagingDir, string filesDir, string version)
        {
            StagingDir = stagingDir;
            FilesDir = filesDir;
            Version = version;
        }

        public string StagingDir { get; }
        public string FilesDir { get; }
        public string Version { get; }
    }

    /// <summary>
    /// Downloads (or takes <paramref name="sourceZip"/>) and unpacks the
    /// launcher into <paramref name="dir"/>\_setup-staging, verified. Changes
    /// nothing else: on any failure or cancel the staging folder is removed
    /// (and <paramref name="dir"/> too, if this created it and it is empty).
    /// </summary>
    /// <param name="setupExePath">Staged as the uninstaller too; null leaves it out.</param>
    public static StagedInstall Stage(string dir, string? sourceZip, LauncherRelease? release,
                                      Action<InstallProgress> report, CancellationToken ct, HttpClient? http = null,
                                      string? setupExePath = null)
    {
        bool existed = Directory.Exists(dir);
        Directory.CreateDirectory(dir);
        var staging = Path.Combine(dir, StagingFolder);
        var stagedFiles = Path.Combine(staging, "files");
        FileOps.DeleteTree(staging);
        Directory.CreateDirectory(staging);

        try
        {
            string zipPath;
            double unpackStart;
            if (sourceZip != null)
            {
                zipPath = sourceZip;
                unpackStart = DownloadStart;
            }
            else
            {
                http ??= Download.Shared;
                release ??= ReleaseFeed.FindLatestAsync(http, ct).GetAwaiter().GetResult();
                var rel = release;
                zipPath = Path.Combine(staging, ReleaseFeed.AssetName);
                SetupLog.Write($"downloading {rel.Version} from {rel.Url}");
                report(new InstallProgress(InstallStage.Downloading, DownloadStart, 0, rel.Size, rel.Version));
                Download.ToFileAsync(http, rel.Url, zipPath, rel.Size, rel.Sha256, (got, total) =>
                {
                    double f = total is long t && t > 0 ? Math.Min(1, (double)got / t) : 0;
                    report(new InstallProgress(InstallStage.Downloading, DownloadStart + (DownloadEnd - DownloadStart) * f, got, total, rel.Version));
                }, ct).GetAwaiter().GetResult();
                unpackStart = DownloadEnd;
            }

            LauncherZip zip;
            try { zip = LauncherZip.Open(zipPath); }
            catch (InvalidDataException ex) when (sourceZip == null)
            {
                SetupLog.Write("downloaded package: " + ex.Message);
                throw new DownloadException(DownloadException.Damaged, ex);
            }
            var spaceProblem = InstallLocation.SpaceProblem(dir, zip.TotalSize);
            if (spaceProblem != null) throw new SetupException(spaceProblem);

            report(new InstallProgress(InstallStage.Unpacking, unpackStart, version: release?.Version));
            double total = Math.Max(1, zip.TotalSize);
            try
            {
                zip.ExtractTo(stagedFiles, bytes =>
                    report(new InstallProgress(InstallStage.Unpacking, unpackStart + (UnpackEnd - unpackStart) * bytes / total, version: release?.Version)), ct);
            }
            catch (InvalidDataException ex) when (sourceZip == null)
            {
                SetupLog.Write("downloaded package: " + ex.Message);
                throw new DownloadException(DownloadException.Damaged, ex);
            }
            if (setupExePath != null)
                StageUninstaller(setupExePath, dir, stagedFiles, release, http, ct);
            ct.ThrowIfCancellationRequested(); // the last chance before the point of no return
            var version = release?.Version ?? StagedVersion(Path.Combine(stagedFiles, InstallLocation.LauncherExe)) ?? "0.0.0";
            return new StagedInstall(staging, stagedFiles, version);
        }
        catch
        {
            FileOps.TryDeleteTree(staging);
            if (!existed) FileOps.DeleteIfEmpty(dir);
            throw;
        }
    }

    // "1.0.40" from the launcher exe's file version (a local --source zip has
    // no release to name it); null when unreadable or an unstamped dev build.
    private static string? StagedVersion(string exe)
    {
        try
        {
            var info = FileVersionInfo.GetVersionInfo(exe);
            if (info.FileMajorPart == 0 && info.FileMinorPart == 0 && info.FileBuildPart == 0) return null;
            return $"{info.FileMajorPart}.{info.FileMinorPart}.{info.FileBuildPart}";
        }
        catch (Exception ex) when (ex is IOException || ex is BadImageFormatException || ex is UnauthorizedAccessException) { return null; }
    }

    // The uninstaller is a copy of this setup app (small now that the launcher
    // is downloaded, not carried) — started with --uninstall it removes Origin.
    // Skipped when Setup is itself running as that uninstall.exe.
    //
    // Staged with the launcher, so a Setup that can't get one fails while nothing
    // has changed. A Setup started from a shared folder can run and still not be
    // copied: 1.0.76 from a Parallels share (\\Mac\Home\Downloads) got "file not
    // found" from File.Copy every time, after the launcher was already in place.
    // So it is read as a plain stream (File.Copy also carries metadata a share may
    // not have), then falls back to this release's own Setup from the feed.
    private static void StageUninstaller(string setupExePath, string installDir, string stagedFiles,
                                         LauncherRelease? release, HttpClient? http, CancellationToken ct)
    {
        var installed = Path.Combine(installDir, InstallLocation.UninstallerExe);
        if (string.Equals(Path.GetFullPath(setupExePath), Path.GetFullPath(installed), StringComparison.OrdinalIgnoreCase)) return;

        var dest = Path.Combine(stagedFiles, InstallLocation.UninstallerExe);
        // Opened first: a folder Setup can't write to is not a "can't read Setup" problem.
        using (var target = new FileStream(dest, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            try
            {
                FileOps.Retry(() =>
                {
                    target.SetLength(0);
                    target.Position = 0;
                    using var source = new FileStream(setupExePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                    source.CopyToAsync(target, 1 << 16, ct).GetAwaiter().GetResult();
                }, attempts: 5);
                return;
            }
            catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException || ex is System.Security.SecurityException)
            {
                SetupLog.Write($"can't read Setup at {setupExePath}: {ex.Message}");
            }
        }

        // The uninstaller is what Add/Remove Programs runs later: never one Setup can't check against the
        // release's own SHA-256 (the page route of the feed has none, so then it is the same as no release).
        if (release?.SetupSha256 == null)
        {
            if (release != null) SetupLog.Write("the release feed gave no SHA-256 for Setup, so the uninstaller is not downloaded");
            throw new SetupException("Setup can't read its own file where it's running from. Move it to a folder on this PC, like Downloads, then try again.");
        }
        SetupLog.Write($"downloading the uninstaller from {release.SetupUrl}");
        Download.ToFileAsync(http ?? Download.Shared, release.SetupUrl, dest, release.SetupSize, release.SetupSha256, null, ct)
            .GetAwaiter().GetResult();
        // Belt and braces after the hash: it must be a program.
        using var downloaded = new FileStream(dest, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (downloaded.ReadByte() != 'M' || downloaded.ReadByte() != 'Z')
        {
            SetupLog.Write("downloaded uninstaller is not a program");
            throw new DownloadException(DownloadException.Damaged);
        }
    }
}
