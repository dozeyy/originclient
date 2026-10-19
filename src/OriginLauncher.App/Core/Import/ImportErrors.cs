using System.IO;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Import;

// What the player is told when something goes wrong: the reason in plain words, never a machine path.
// Exception messages from the file system carry full paths ("Access to the path 'C:\Users\...' is
// denied"); those would land in summary.txt and the panel, so every absolute path in a message is cut
// down to its file name.
public static class ImportErrors
{
    private static readonly Regex AbsolutePath = new(
        @"(?<![\w.])(?:[A-Za-z]:[\\/]|\\\\|/)[^\s'""<>|*?]+",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);

    public static string Plain(Exception ex) => Plain(ex.Message);

    public static string Plain(string message)
    {
        var text = AbsolutePath.Replace(message, m =>
        {
            var name = Path.GetFileName(m.Value.TrimEnd('/', '\\'));
            return name.Length == 0 ? "a folder" : "\"" + name + "\"";
        });
        return text.Replace("\"\"", "\"").Trim().TrimEnd('.');
    }
}
