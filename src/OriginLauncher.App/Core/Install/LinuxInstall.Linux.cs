using System.IO;

namespace OriginLauncher.App.Core.Install;

// Which of the three Linux installs (tools/linux/package.sh) this process runs from, worked out in one
// place so the self-update (Core/Updates/UpdateService.Linux.cs) and "start at login"
// (InstallRegistry.Linux.cs) can never disagree.
internal static class LinuxInstall
{
    /// <summary>Where the app sits inside the AppImage (tools/linux/package.sh copies it there, AppRun starts it).</summary>
    internal const string AppImageAppFolder = "usr/lib/origin-launcher";

    /// <summary>The marker file a .deb/.rpm puts next to the executable (tools/linux/package.sh).</summary>
    internal const string PackageMarkerName = "package-install";

    /// <summary>What a .deb/.rpm puts on PATH: it starts the newer of the package and the per-user copy (tools/linux/files/origin-launcher).</summary>
    internal const string PackageLauncher = "/usr/bin/origin-launcher";

    /// <summary>The running .AppImage file, or null when this is not an AppImage run.</summary>
    internal static readonly string? AppImage = ResolveAppImage(
        Environment.GetEnvironmentVariable("APPIMAGE"), Environment.GetEnvironmentVariable("APPDIR"), AppContext.BaseDirectory);

    /// <summary>
    /// $APPIMAGE, but only when this process really runs from that image. The AppImage runtime sets APPIMAGE
    /// (the image file) and APPDIR (its own mount, or the extracted folder) for everything started under it,
    /// so a tar.gz or package build started from a terminal inside another AppImage (Cursor, VS Code) inherits
    /// that app's values. Trusting them would replace the other app's file on update and point the login entry
    /// at it. Our own image runs us from $APPDIR/usr/lib/origin-launcher, so that has to be this folder.
    /// A path that differs only by a symlink fails safe: the run counts as a plain folder, whose read-only
    /// mount then offers no self-update at all, never a wrong one.
    /// </summary>
    internal static string? ResolveAppImage(string? appImage, string? appDir, string baseDirectory)
    {
        if (string.IsNullOrEmpty(appImage) || !Path.IsPathRooted(appImage) || !File.Exists(appImage)) return null;
        if (string.IsNullOrEmpty(appDir) || !Path.IsPathRooted(appDir)) return null;
        return SameFolder(Path.Combine(appDir, AppImageAppFolder), baseDirectory) ? appImage : null;
    }

    internal static bool IsPackageFolder(string folder) => File.Exists(Path.Combine(folder, PackageMarkerName));

    /// <summary>
    /// What the login entry starts. An AppImage: the image file (its mount path changes every run). A .deb/.rpm,
    /// or the per-user copy its self-update installed (<paramref name="userAppCopy"/>): the package's own
    /// /usr/bin/origin-launcher, which picks whichever of the two is newer, so a login after a self-update
    /// never starts the older package build (gated, downloading the update again at every login). Anything
    /// else (a tar.gz folder): the executable itself. <paramref name="fileExists"/> is File.Exists outside tests.
    /// </summary>
    internal static string LoginTarget(string exePath, string? appImage, string userAppCopy, Func<string, bool> fileExists)
    {
        if (appImage != null) return appImage;
        var folder = Path.GetDirectoryName(exePath);
        var fromPackage = folder != null && (IsPackageFolder(folder) || SameFolder(folder, userAppCopy));
        // A package removed while its per-user copy stayed: nothing on PATH to start, so start the copy itself.
        return fromPackage && fileExists(PackageLauncher) ? PackageLauncher : exePath;
    }

    internal static bool SameFolder(string a, string b) => string.Equals(
        Path.TrimEndingDirectorySeparator(Path.GetFullPath(a)),
        Path.TrimEndingDirectorySeparator(Path.GetFullPath(b)),
        StringComparison.Ordinal);
}
