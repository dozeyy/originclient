using System.Collections.Concurrent;
using System.IO;

namespace OriginLauncher.App.Core.GameFolders;

/// <summary>
/// The folder each game folder's latest launch really loaded its mods from, so the crash check reads exactly that
/// set (CrashAnalyzer.ReadMods). VersionManager decides it per launch: the version's shared folder, mods-shared
/// (the player's copies replace some of Origin's), mods-origin-only ("Your mods" off), mods-profile or
/// mods-profile-&lt;configuration&gt; (a mod profile), or on 1.8.9 the account's own mirrored mods/ folder.
/// Before 2026-11-04 the crash check guessed from fixed folder names: it counted the player's loose jars after an
/// Origin-only launch (a "Try once without my mods" that relaunched the identical set), missed a profile's
/// mods-profile-&lt;id&gt; jars (no "Turn off" button), and read a stale mods-profile from an old launch.
///
/// In memory only: a game this launcher run did not start never reaches the crash check. One game per account and
/// version runs at a time, so the game folder is an exact key.
/// </summary>
public static class LaunchedModsFolder
{
    private static readonly ConcurrentDictionary<string, string> ByGameDir = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Called by the launch once it knows the folder it passes the game (fabric.modsFolder, or 1.8.9's mods/).</summary>
    public static void Record(string gameDir, string modsFolder) => ByGameDir[Key(gameDir)] = modsFolder;

    /// <summary>The folder the latest launch of <paramref name="gameDir"/> loaded mods from, or null when unknown.</summary>
    public static string? For(string gameDir) => ByGameDir.TryGetValue(Key(gameDir), out var folder) ? folder : null;

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
