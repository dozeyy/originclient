using System.IO;

namespace OriginLauncher.App.Core;

// Linux: per-user data under $XDG_DATA_HOME/OriginLauncher, which the XDG base-directory spec
// defaults to ~/.local/share when the variable is unset, empty or not an absolute path (the spec
// says a relative value must be ignored). The shipped files sit next to the executable: in the
// tar.gz build that is the unpacked folder, in the AppImage it is the image's own mount
// (read-only, which is fine: Bundled/ is never written).
public static partial class OriginPaths
{
    internal static string DefaultRoot => Path.Combine(XdgDir("XDG_DATA_HOME", ".local", "share"), "OriginLauncher");

    private static string BundledBase => AppContext.BaseDirectory;

    /// <summary>An XDG base directory: the variable when it holds an absolute path, else ~/&lt;fallback&gt;.</summary>
    internal static string XdgDir(string variable, params string[] fallbackUnderHome)
    {
        var value = Environment.GetEnvironmentVariable(variable);
        if (!string.IsNullOrEmpty(value) && Path.IsPathRooted(value)) return value;
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return Path.Combine([home, .. fallbackUnderHome]);
    }
}
