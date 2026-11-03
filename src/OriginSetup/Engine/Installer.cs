using System.Diagnostics;
using System.Net.Http;
using System.Threading;
using OriginLauncher.App.Core.Install;

namespace OriginSetup.Engine;

/// <summary>What the player chose. Null RamMb/DefaultVersion = leave the launcher's own default.</summary>
public sealed class InstallOptions
{
    public string Directory { get; set; } = InstallLocation.Default;
    public bool StartWithWindows { get; set; }
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
//  - After the move begins there is no cancel — the rest takes a second.
//  - Taking over an install made by the old Inno Setup installer replaces its
//    Add/Remove Programs entry (same key) and removes its uninstaller files.
public static class Installer
{
    private const string StagingFolder = "_setup-staging";

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
    public static void Run(string setupExePath, InstallOptions options, LauncherRelease? release,
                           Action<InstallProgress> report, CancellationToken ct, HttpClient? http = null)
    {
        var dir = options.Directory;
        SetupLog.Write($"install -> {dir} (source={options.SourceZip ?? "download"}, startup={options.StartWithWindows}, ram={options.RamMb?.ToString() ?? "default"}, version={options.DefaultVersion ?? "default"}, settingsSync={options.SettingsSync?.ToString() ?? "unchanged"})");
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
        FileOps.MoveTree(staged.FilesDir, dir);
        FileOps.TryDeleteTree(staged.StagingDir);
        foreach (var legacy in new[] { "unins000.exe", "unins000.dat", "unins000.msg" })
            FileOps.Retry(() => { var p = Path.Combine(dir, legacy); if (File.Exists(p)) File.Delete(p); });

        report(new InstallProgress(InstallStage.Finishing, 0.97, version: version));
        var exe = Path.Combine(dir, InstallLocation.LauncherExe);
        Shortcuts.DeleteLegacyGroup();
        Shortcuts.Create(Shortcuts.StartMenuPath, exe, "Origin - the Minecraft launcher for every version");
        Shortcuts.Create(Shortcuts.DesktopPath, exe, "Origin - the Minecraft launcher for every version");
        Registration.Write(dir, version);
        InstallRegistry.SetStartWithWindows(options.StartWithWindows, exe);
        Handoff.Write(options.RamMb, options.DefaultVersion, options.SettingsSync);

        SetupLog.Write($"install complete ({version})");
        report(new InstallProgress(InstallStage.Done, 1, version: version));
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

        if (release == null)
            throw new SetupException("Setup can't read its own file where it's running from. Move it to a folder on this PC, like Downloads, then try again.");
        SetupLog.Write($"downloading the uninstaller from {release.SetupUrl}");
        Download.ToFileAsync(http ?? Download.Shared, release.SetupUrl, dest, release.SetupSize, release.SetupSha256, null, ct)
            .GetAwaiter().GetResult();
        // The feed's page route gives no size or hash; at least it must be a program.
        using var downloaded = new FileStream(dest, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (downloaded.ReadByte() != 'M' || downloaded.ReadByte() != 'Z')
        {
            SetupLog.Write("downloaded uninstaller is not a program");
            throw new DownloadException(DownloadException.Damaged);
        }
    }
}
