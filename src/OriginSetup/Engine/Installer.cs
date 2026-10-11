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
}

public enum InstallStage { Preparing, Unpacking, Finishing, Done }

/// <summary>A problem the player can act on, worded for them.</summary>
public sealed class SetupException : Exception
{
    public SetupException(string message) : base(message) { }
}

// Installs (or updates/repairs) Origin from the payload inside setup.exe.
//
// Safe by construction:
//  - Files unpack into a staging folder INSIDE the install folder first; only
//    a complete, checksum-verified unpack is moved into place. Cancelling or a
//    damaged download leaves an existing install exactly as it was.
//  - After the move begins there is no cancel — the rest takes a second.
//  - Taking over an install made by the old Inno Setup installer replaces its
//    Add/Remove Programs entry (same key) and removes its uninstaller files.
public static class Installer
{
    private const string StagingFolder = "_setup-staging";

    public static void Run(Payload payload, string setupExePath, InstallOptions options,
                           Action<InstallStage, double> report, CancellationToken ct)
    {
        var dir = options.Directory;
        SetupLog.Write($"install {payload.AppVersion} -> {dir} (startup={options.StartWithWindows}, ram={options.RamMb?.ToString() ?? "default"}, version={options.DefaultVersion ?? "default"})");
        report(InstallStage.Preparing, 0);

        var problem = InstallLocation.Problem(dir, InstallLocation.ProtectedRoots())
                      ?? InstallLocation.ProbeWritable(dir)
                      ?? InstallLocation.SpaceProblem(dir, payload.TotalSize);
        if (problem != null) throw new SetupException(problem);
        if (LauncherProcess.IsRunning())
            throw new SetupException("Origin Launcher is still open. Close it and try again.");

        bool existed = Directory.Exists(dir);
        Directory.CreateDirectory(dir);
        var staging = Path.Combine(dir, StagingFolder);
        FileOps.DeleteTree(staging);

        try
        {
            double total = Math.Max(1, payload.TotalSize);
            payload.ExtractTo(staging, bytes => report(InstallStage.Unpacking, 0.02 + 0.86 * bytes / total), ct);
        }
        catch
        {
            FileOps.TryDeleteTree(staging);
            if (!existed) FileOps.DeleteIfEmpty(dir);
            throw;
        }

        // Point of no return.
        report(InstallStage.Finishing, 0.9);
        FileOps.MoveTree(staging, dir);
        FileOps.TryDeleteTree(staging);
        foreach (var legacy in new[] { "unins000.exe", "unins000.dat", "unins000.msg" })
            FileOps.Retry(() => { var p = Path.Combine(dir, legacy); if (File.Exists(p)) File.Delete(p); });
        WriteUninstaller(setupExePath, payload.PayloadOffset, Path.Combine(dir, InstallLocation.UninstallerExe));

        report(InstallStage.Finishing, 0.95);
        var exe = Path.Combine(dir, InstallLocation.LauncherExe);
        Shortcuts.DeleteLegacyGroup();
        Shortcuts.Create(Shortcuts.StartMenuPath, exe, "Origin — the Minecraft launcher for every version");
        Shortcuts.Create(Shortcuts.DesktopPath, exe, "Origin — the Minecraft launcher for every version");
        Registration.Write(dir, payload.AppVersion);
        InstallRegistry.SetStartWithWindows(options.StartWithWindows, exe);
        Handoff.Write(options.RamMb, options.DefaultVersion);

        SetupLog.Write("install complete");
        report(InstallStage.Done, 1);
    }

    // The uninstaller is this very setup app minus the payload appended to it
    // (the first payloadOffset bytes are the bare compiled exe).
    private static void WriteUninstaller(string setupExePath, long payloadOffset, string dest)
    {
        FileOps.Retry(() =>
        {
            using var src = new FileStream(setupExePath, FileMode.Open, FileAccess.Read, FileShare.Read);
            using var dst = new FileStream(dest, FileMode.Create, FileAccess.Write, FileShare.None);
            var buffer = new byte[1 << 16];
            long left = payloadOffset;
            while (left > 0)
            {
                int n = src.Read(buffer, 0, (int)Math.Min(buffer.Length, left));
                if (n == 0) throw new EndOfStreamException("Setup file is incomplete.");
                dst.Write(buffer, 0, n);
                left -= n;
            }
        });
    }
}
