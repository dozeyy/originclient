using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core;

/// <summary>
/// What is wrong with a value typed into a Settings field, in words a player can act on, or null when it is fine.
/// Pure (no WPF, no file system, and no Path.* calls; the folder rule takes the OS as an argument, so every OS's
/// answer is the same on any test runner), so a test holds it. A field with a problem shows a Danger edge and this sentence under the box and
/// is NOT saved; the text stays as typed so the player can fix it.
/// </summary>
public static class FieldRules
{
    public const int MinWidth = 320;
    public const int MinHeight = 240;

    // A drive and a slash ("C:\Games", "D:/"), or a network share ("\\server\share").
    private static readonly Regex FullPath = new(@"^(?:[A-Za-z]:[\\/]|\\\\[^\\/]+[\\/][^\\/]+)", RegexOptions.Compiled);
    private static readonly char[] Forbidden = ['*', '?', '"', '<', '>', '|'];

    /// <summary>Whose path rules a folder follows: each launcher passes its own OS (the default is the running one).</summary>
    public enum FolderOs { Windows, Mac, Linux }

    private static FolderOs ThisOs =>
        OperatingSystem.IsWindows() ? FolderOs.Windows : OperatingSystem.IsMacOS() ? FolderOs.Mac : FolderOs.Linux;

    /// <summary>The game folder on the OS this launcher runs on (see the overload).</summary>
    public static string? InstallFolderProblem(string? text) => InstallFolderProblem(text, ThisOs);

    /// <summary>
    /// The game folder must be a full path with no illegal characters. Windows: a drive and a folder, or a UNC share,
    /// without * ? " &lt; &gt; |. macOS and Linux: it starts at "/" (a typed "~" is not expanded, so ~/Games is refused
    /// with an example that is written out), and only control characters are refused (Unix allows every other one).
    /// </summary>
    public static string? InstallFolderProblem(string? text, FolderOs os)
    {
        var path = text?.Trim();
        if (string.IsNullOrEmpty(path)) return "Enter a folder.";
        if (os != FolderOs.Windows)
        {
            if (path.Any(char.IsControl)) return "That folder name has a character that isn't allowed.";
            if (path[0] != '/')
                return os == FolderOs.Mac
                    ? "Use a full folder path, like /Users/you/Games/Origin."
                    : "Use a full folder path, like /home/you/Games/Origin.";
            return null;
        }
        if (path.IndexOfAny(Forbidden) >= 0 || path.Any(char.IsControl))
            return "That folder name has a character Windows does not allow.";
        // "C:Games" and "\Games" are rooted but not full paths; a drive letter needs its slash.
        if (!FullPath.IsMatch(path)) return "Use a full folder path, like C:\\Games\\Origin.";
        return null;
    }

    /// <summary>A window size: a whole number of at least <paramref name="minimum"/> pixels.</summary>
    public static string? ResolutionProblem(string? text, int minimum, string what)
    {
        if (!int.TryParse(text?.Trim(), out var value)) return $"{what} must be a whole number.";
        if (value < minimum) return $"{what} must be at least {minimum} pixels.";
        return null;
    }
}
