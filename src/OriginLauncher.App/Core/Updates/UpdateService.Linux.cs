using System.Diagnostics;
using System.Formats.Tar;
using System.IO;
using System.IO.Compression;
using System.Runtime.InteropServices;
using OriginLauncher.App.Core.Install;

namespace OriginLauncher.App.Core.Updates;

// Linux: three ways Origin is installed (tools/linux/package.sh builds all of them), and each updates itself:
//  - AppImage ($APPIMAGE, trusted only when $APPDIR is this process's own image: Core/Install/LinuxInstall.Linux.cs):
//    the release asset is
//    OriginLauncher-linux-<x86_64|aarch64>.AppImage, one file. It is staged as
//    <stage>/OriginLauncher.AppImage; the swap copies it next to the old image and renames it over
//    that (one rename in one folder), then starts it.
//  - tar.gz (an unpacked folder anywhere the player can write): the asset is
//    OriginLauncher-linux-<x64|arm64>.tar.gz, unpacked into the stage folder (tar keeps the
//    executable bits). The swap copies it next to the app folder, moves the old folder aside,
//    renames the new one into its place, puts the old one back if that fails, and starts it.
//  - .deb / .rpm (a root-owned /opt/origin-launcher holding the package-install marker): the package
//    folder is never touched (the package manager owns it). The tar.gz is swapped into a per-user
//    copy instead, OriginPaths.UserAppCopy (~/.local/share/OriginLauncher/app), exactly like a
//    tar.gz install, and /usr/bin/origin-launcher (tools/linux/files/origin-launcher) starts that
//    copy whenever its VERSION is newer than the package's; a newer package from the package
//    manager wins again the same way. One mechanism, so the mandatory-update rule is the same.
// Shared code (UpdateService.cs) has already refused a release with a missing or wrong SHA-256
// before any of this runs (DECISIONS 2026-10-25), and a failure here never blocks Play (UpdateGate).
// Origin installed somewhere else it can't replace itself (a read-only folder with no marker, a
// Flatpak, `dotnet OriginLauncher.dll`) is never offered an update at all: such a swap could only
// fail, and a mandatory update that can never apply would gate Play on every start.
// Asset names are fixed: the website links to them, and tools/release/check_consistency.py checks
// that the release workflow and tools/linux/package.sh use exactly these, each with its .sha256.
public static partial class UpdateService
{
    private static readonly bool Arm64 = RuntimeInformation.ProcessArchitecture == Architecture.Arm64;

    internal static readonly string TarAssetName = Arm64 ? "OriginLauncher-linux-arm64.tar.gz" : "OriginLauncher-linux-x64.tar.gz";
    internal static readonly string AppImageAssetName = Arm64 ? "OriginLauncher-linux-aarch64.AppImage" : "OriginLauncher-linux-x86_64.AppImage";

    /// <summary>The running .AppImage file, or null when this is not an AppImage run.</summary>
    internal static readonly string? AppImagePath = LinuxInstall.AppImage;

    /// <summary>True for a .deb/.rpm install: updates go to the per-user copy, never the package folder.</summary>
    internal static readonly bool PackageInstall = AppImagePath == null && LinuxInstall.IsPackageFolder(AppFolder);

    /// <summary>The folder a tar.gz swap replaces: the running app folder, or a package install's per-user copy.</summary>
    internal static string FolderSwapTarget(bool packageInstall, string appFolder) =>
        packageInstall ? OriginPaths.UserAppCopy : appFolder;

    /// <summary>Why this install can't replace itself, or null when it can.</summary>
    internal static readonly string? NotUpdatableReason = FindNotUpdatableReason();

    // A name no release asset has, so an install that can't swap itself is never offered (or gated on) an update.
    internal static readonly string ReleaseAssetName =
        NotUpdatableReason != null ? "(no self-update: " + NotUpdatableReason + ")"
        : AppImagePath != null ? AppImageAssetName : TarAssetName;

    // The local download's file name (UpdateService.cs adds -<version>.zip whatever the content is).
    private static readonly string ReleaseZipPrefix =
        AppImagePath != null ? Path.GetFileNameWithoutExtension(AppImageAssetName) : TarAssetName[..^".tar.gz".Length];

    internal const string AppImageStageName = "OriginLauncher.AppImage";

    // No changed-files updates on Linux: the download is a tar.gz or an AppImage (not a zip of loose files) and is staged
    // its own way. It still gets the background download (UpdateService.cs).
    private static readonly string? DeltaManifestName = null;

    /// <summary>How the app quits for the swap. The UI start replaces this with its own lifetime's shutdown.</summary>
    public static Action Shutdown { get; set; } = () => Environment.Exit(0);

    private static void ShutdownApp() => Shutdown();

    // The download is not a zip on Linux: unpack the tar.gz, or stage the AppImage as one file.
    // Nothing refuses an update up front on Linux: a read-only package install updates into the per-user copy.
    private static Task<string?> InstallBlockedHereAsync() => Task.FromResult<string?>(null);

    // Never reached (StageReleaseOverride always stages); kept so UpdateService.cs has one extract call for every OS.
    private static void ExtractUpdate(string zipPath, string stageDir) =>
        ZipFile.ExtractToDirectory(zipPath, stageDir, overwriteFiles: true);

    static partial void StageReleaseOverride(string download, string stageDir, ref bool staged)
    {
        StageRelease(download, stageDir, appImage: AppImagePath != null);
        staged = true;
    }

    internal static void StageRelease(string download, string stageDir, bool appImage)
    {
        Directory.CreateDirectory(stageDir);
        if (appImage)
        {
            var target = Path.Combine(stageDir, AppImageStageName);
            File.Move(download, target, overwrite: true);
            File.SetUnixFileMode(target, (UnixFileMode)0b111_101_101); // 0755
            return;
        }
        using var file = File.OpenRead(download);
        using var gzip = new GZipStream(file, CompressionMode.Decompress);
        // TarFile refuses entries and link targets that would land outside the stage folder.
        TarFile.ExtractToDirectory(gzip, stageDir, overwriteFiles: true);
    }

    // Paths travel as script arguments ("$2", "$3"), never pasted into the script text, so no path
    // can change what the script does. Every way out starts the launcher again: the new one when the
    // swap worked, else the old one, which stays exactly where it was.
    internal const string ApplyScript = """
#!/bin/sh
# Origin Launcher self-update (Linux): wait for the launcher to quit, swap in the new build, start it.
# $1 launcher pid, $2 stage folder, $3 the .AppImage file or the app folder, $4 "appimage" or "folder",
# $5 (optional) the launcher to start when $3 holds none (a package install's first per-user copy failed).
pid="$1"; stage="$2"; target="$3"; mode="$4"; fallback="$5"
while kill -0 "$pid" 2>/dev/null; do sleep 1; done
start() {
  if [ "$mode" = appimage ]; then exe="$target"; else exe="$target/OriginLauncher"; fi
  if [ ! -x "$exe" ] && [ -n "$fallback" ]; then exe="$fallback"; fi
  if command -v setsid >/dev/null 2>&1; then exec setsid "$exe" </dev/null >/dev/null 2>&1; fi
  exec "$exe" </dev/null >/dev/null 2>&1
}
next="$target.new"
if [ "$mode" = appimage ]; then
  new="$stage/OriginLauncher.AppImage"
  [ -f "$new" ] || start
  rm -f "$next"
  if cp "$new" "$next" && chmod 755 "$next" && mv -f "$next" "$target"; then rm -rf "$stage"; else rm -f "$next"; fi
  start
fi
# folder: the tar's single top folder, or the stage itself when the files sit at its top
new="$stage"
set -- "$stage"/*
if [ $# -eq 1 ] && [ -d "$1" ]; then new="$1"; fi
[ -x "$new/OriginLauncher" ] || start
old="$target.previous"
mkdir -p "$(dirname "$target")" || start
rm -rf "$next" "$old"
cp -a "$new" "$next" || { rm -rf "$next"; start; }
# no copy there yet (a package install's first update): the new one simply takes the place
if [ ! -e "$target" ]; then
  if mv "$next" "$target"; then rm -rf "$stage"; else rm -rf "$next"; fi
  start
fi
if mv "$target" "$old"; then
  if mv "$next" "$target"; then rm -rf "$old" "$stage"; else mv "$old" "$target"; rm -rf "$next"; fi
else
  rm -rf "$next"
fi
start

""";

    private static void StartApplyScript(string stageDir, string updatesRoot)
    {
        if (NotUpdatableReason != null) throw new InvalidOperationException("This install can't update itself: " + NotUpdatableReason);
        var script = Path.Combine(updatesRoot, "apply-update.sh");
        File.WriteAllText(script, ApplyScript.ReplaceLineEndings("\n")); // sh reads a CR as part of each command
        var psi = new ProcessStartInfo("/bin/sh") { UseShellExecute = false };
        psi.ArgumentList.Add(script);
        psi.ArgumentList.Add(Environment.ProcessId.ToString());
        psi.ArgumentList.Add(stageDir);
        psi.ArgumentList.Add(AppImagePath ?? FolderSwapTarget(PackageInstall, AppFolder));
        psi.ArgumentList.Add(AppImagePath != null ? "appimage" : "folder");
        psi.ArgumentList.Add(Environment.ProcessPath ?? ""); // the launcher running now restarts if the swap fails
        Process.Start(psi);
    }

    private static string AppFolder => AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);

    private static string? FindNotUpdatableReason()
    {
        if (!OperatingSystem.IsLinux()) return "not Linux";
        if (AppImagePath != null)
            return Writable(Path.GetDirectoryName(AppImagePath)!) ? null : "the AppImage's folder is read-only";
        if (!string.IsNullOrEmpty(Environment.GetEnvironmentVariable("FLATPAK_ID")) || File.Exists("/.flatpak-info"))
            return "Flatpak updates through Flatpak";
        var exe = Environment.ProcessPath;
        if (exe == null || Path.GetFileName(exe) != "OriginLauncher"
            || !string.Equals(Path.GetDirectoryName(exe), AppFolder, StringComparison.Ordinal))
            return "not started as OriginLauncher from its own folder";
        // A package folder stays read-only on purpose: the swap writes the per-user copy instead.
        if (PackageInstall) return null;
        var parent = Path.GetDirectoryName(AppFolder);
        if (parent == null || !Writable(parent) || !Writable(AppFolder)) return "the install folder is read-only";
        return null;
    }

    private const int W_OK = 2;

    // access(2) answers for this user without writing anything (root-owned /opt, a read-only mount).
    private static bool Writable(string folder) => Directory.Exists(folder) && access(folder, W_OK) == 0;

    [DllImport("libc", SetLastError = true)]
    private static extern int access([MarshalAs(UnmanagedType.LPUTF8Str)] string path, int mode);
}
