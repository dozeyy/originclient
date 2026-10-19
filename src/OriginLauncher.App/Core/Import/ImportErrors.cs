using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Import;

// What the player is told when something goes wrong: the reason in plain words, never a machine path.
// Exception messages from the file system carry full paths ("Access to the path 'C:\Users\...' is
// denied"); those would land in summary.txt and the panel, so every absolute path in a message is cut
// down to its file name.
//  * A quoted path ('C:\Users\Will Henry\...' — .NET quotes the paths it reports) is taken whole, spaces
//    included, before anything else; then bare paths up to the first space.
//  * Both '\' and '/' split a path on every OS: a Windows path in a message must be trimmed on Linux too
//    (Path.GetFileName there splits only on '/', which leaked "C:\Users\..." in CI).
public static class ImportErrors
{
    private const string PathStart = @"(?:[A-Za-z]:[\\/]|\\\\|/)";

    private static readonly Regex QuotedPath = new(
        @"(['""])(" + PathStart + @"[^'""]*)\1",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);

    private static readonly Regex AbsolutePath = new(
        @"(?<![\w.])" + PathStart + @"[^\s'""<>|*?]+",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);

    public static string Plain(Exception ex) => Plain(ex.Message);

    public static string Plain(string message)
    {
        var text = QuotedPath.Replace(message, m => NameOf(m.Groups[2].Value));
        text = AbsolutePath.Replace(text, m => NameOf(m.Value));
        return text.Replace("\"\"", "\"").Trim().TrimEnd('.');
    }

    // The last segment, split on either separator; a bare root or drive is "a folder".
    private static string NameOf(string path)
    {
        var trimmed = path.TrimEnd('/', '\\');
        var name = trimmed[(trimmed.LastIndexOfAny(new[] { '/', '\\' }) + 1)..];
        return name.Length == 0 || name.EndsWith(':') ? "a folder" : "\"" + name + "\"";
    }
}
