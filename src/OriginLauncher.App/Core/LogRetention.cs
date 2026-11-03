using System.IO;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core;

/// <summary>
/// Keeps the launcher's logs folder from growing forever. Every launch writes a full copy of the game's console
/// (&lt;version&gt;_&lt;account&gt;_&lt;time&gt;.log), and launcher errors and crashes add launcher_error_&lt;time&gt;.log and
/// crash_&lt;time&gt;.log; nothing ever removed them. On each start, only those time-stamped files are pruned: the
/// newest <see cref="KeepNewest"/> stay while they fit in <see cref="KeepBytes"/>, and the newest
/// <see cref="AlwaysKeep"/> stay whatever their size (the crash window and a bug report read the latest ones). The
/// fixed-name logs (update.log, settings-sync.log, ...) are not touched.
/// Runs at start, before this launcher has started any game, so no file it deletes is still being written by it.
/// </summary>
public static class LogRetention
{
    public const int KeepNewest = 30;
    public const long KeepBytes = 200L * 1024 * 1024;
    public const int AlwaysKeep = 3;

    // "..._20261103_142501.log": the stamp every per-launch, launcher_error and crash log ends with.
    private static readonly Regex Stamped = new(@"_\d{8}_\d{6}\.log$", RegexOptions.CultureInvariant | RegexOptions.IgnoreCase);

    /// <summary>Prunes <see cref="OriginPaths.Logs"/> on a worker thread. Never throws.</summary>
    public static void StartPrune() => Task.Run(() => Prune(OriginPaths.Logs));

    /// <summary>Deletes the time-stamped logs past the limits; returns their names. Never throws.</summary>
    public static IReadOnlyList<string> Prune(string folder)
    {
        var removed = new List<string>();
        try
        {
            if (!Directory.Exists(folder)) return removed;
            var stamped = new DirectoryInfo(folder).EnumerateFiles()
                .Where(f => Stamped.IsMatch(f.Name))
                .OrderByDescending(f => f.LastWriteTimeUtc)
                .ToList();
            long total = 0;
            for (var i = 0; i < stamped.Count; i++)
            {
                total += stamped[i].Length;
                if (i < AlwaysKeep || (i < KeepNewest && total <= KeepBytes)) continue;
                try
                {
                    stamped[i].Delete();
                    removed.Add(stamped[i].Name);
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    // Open in another program (an editor, a support tool): kept, tried again next start.
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Folder unreadable right now: nothing pruned this start.
        }
        return removed;
    }
}
