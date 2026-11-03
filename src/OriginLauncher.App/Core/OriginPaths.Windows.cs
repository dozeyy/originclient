using System.IO;

namespace OriginLauncher.App.Core;

// Windows: per-user data under %LocalAppData%\OriginLauncher (no admin); the shipped files
// (Bundled\...) sit next to the exe, wherever the installer put it.
public static partial class OriginPaths
{
    private static string DefaultRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OriginLauncher");

    private static string BundledBase => AppContext.BaseDirectory;
}
