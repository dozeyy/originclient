using System.IO;

namespace OriginLauncher.App.Core.Import.Launcher;

// One line per fact into %LocalAppData%\OriginLauncher\logs\import-launcher.log (the launcher's
// own log folder): scan timing, startup recovery timing, version-gate trouble. Never file
// contents, never account data. A logging problem never reaches the caller. Never creates the logs
// folder itself outside the normal launcher start (EnsureScaffold makes it): no log, no folder.
public static class ImportLog
{
    private static readonly object Gate = new();

    public static void Write(string message)
    {
        try
        {
            lock (Gate)
            {
                if (!Directory.Exists(OriginPaths.Logs)) return;
                var path = Path.Combine(OriginPaths.Logs, "import-launcher.log");
                if (File.Exists(path) && new FileInfo(path).Length > 256 * 1024) File.Delete(path);
                File.AppendAllText(path, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}{Environment.NewLine}");
            }
        }
        catch (Exception)
        {
            // a courtesy
        }
    }
}
