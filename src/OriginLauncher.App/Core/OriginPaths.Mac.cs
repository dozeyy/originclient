using System.IO;

namespace OriginLauncher.App.Core;

// Mac: per-user data under ~/Library/Application Support/OriginLauncher, where Mac apps keep
// their data (.NET's LocalApplicationData on macOS is ~/.local/share, a Linux habit, so the
// folder is built from the home folder instead). The shipped files live in the app bundle's
// Contents/Resources (the executable runs from Contents/MacOS, and codesign wants data files in
// Resources); outside a bundle (a plain publish folder, the CI self-test) they sit next to the
// executable, like on Windows.
public static partial class OriginPaths
{
    internal static string DefaultRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Library", "Application Support", "OriginLauncher");

    private static string BundledBase => MacBundleResources ?? AppContext.BaseDirectory;

    /// <summary>The bundle's Contents/Resources when running from X.app/Contents/MacOS, else null.</summary>
    internal static string? MacBundleResources
    {
        get
        {
            var macOs = new DirectoryInfo(AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar));
            var contents = macOs.Parent;
            if (macOs.Name != "MacOS" || contents?.Name != "Contents"
                || contents.Parent?.Name.EndsWith(".app", StringComparison.OrdinalIgnoreCase) != true)
                return null;
            return Path.Combine(contents.FullName, "Resources");
        }
    }
}
