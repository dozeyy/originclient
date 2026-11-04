using System.Collections.Concurrent;
using System.IO;

namespace OriginLauncher.App.Core.GameFolders;

/// <summary>
/// What each game folder's latest launch really loaded, kept for the screens that explain that game afterwards.
///
/// The mods folder, so the crash check reads exactly that set (CrashAnalyzer.ReadMods). VersionManager decides it per
/// launch: the version's shared folder, mods-shared (the player's copies replace some of Origin's), mods-origin-only
/// ("Your mods" off), mods-profile or mods-profile-&lt;configuration&gt; (a mod profile), or on 1.8.9 the account's
/// own mirrored mods/ folder. Before 2026-11-04 the crash check guessed from fixed folder names: it counted the
/// player's loose jars after an Origin-only launch (a "Try once without my mods" that relaunched the identical set),
/// missed a profile's mods-profile-&lt;id&gt; jars (no "Turn off" button), and read a stale mods-profile.
///
/// The player-override notices (Compat/PlayerOverrides.Notices: "Shaders are off for this profile...", "Origin's X
/// is off..."), so Home can say them when the game is running instead of only writing them to a log.
///
/// In memory only: a game this launcher run did not start never reaches these screens. One game per account and
/// version runs at a time, so the game folder is an exact key.
/// </summary>
public static class LaunchedMods
{
    private sealed record Launch(string ModsFolder, IReadOnlyList<string> Notices);

    private static readonly ConcurrentDictionary<string, Launch> ByGameDir = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Called by the launch once it knows the folder it passes the game (fabric.modsFolder, or 1.8.9's mods/).</summary>
    public static void Record(string gameDir, string modsFolder, IReadOnlyList<string>? notices = null) =>
        ByGameDir[Key(gameDir)] = new Launch(modsFolder, notices ?? Array.Empty<string>());

    /// <summary>The folder the latest launch of <paramref name="gameDir"/> loaded mods from, or null when unknown.</summary>
    public static string? FolderFor(string gameDir) => ByGameDir.TryGetValue(Key(gameDir), out var l) ? l.ModsFolder : null;

    /// <summary>The player-override notices of the latest launch of <paramref name="gameDir"/> (empty when none).</summary>
    public static IReadOnlyList<string> NoticesFor(string gameDir) =>
        ByGameDir.TryGetValue(Key(gameDir), out var l) ? l.Notices : Array.Empty<string>();

    /// <summary>
    /// Home's running line with the launch's notices after it: the first one in full, the rest counted (all of them
    /// are in logs/mod-compat-&lt;version&gt;.log). Just <paramref name="runningLine"/> when there are none.
    /// </summary>
    public static string WithNotices(string runningLine, IReadOnlyList<string> notices) =>
        notices.Count == 0 ? runningLine
        : $"{runningLine}. {notices[0]}{(notices.Count > 1 ? $" (and {notices.Count - 1} more)" : "")}";

    internal static void Forget(string gameDir) => ByGameDir.TryRemove(Key(gameDir), out _);

    private static string Key(string gameDir)
    {
        try
        {
            return Path.TrimEndingDirectorySeparator(Path.GetFullPath(gameDir));
        }
        catch (Exception e) when (e is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return gameDir;
        }
    }
}
