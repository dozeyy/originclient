using System.IO;

namespace OriginLauncher.App.Core.Sync;

// Every Settings Sync write goes through here: write a sibling temp file, then
// rename it over the target. A same-directory rename is atomic on Windows, so a
// crash or power cut leaves either the old file or the new one — never a
// half-written options.txt that would reset the player's settings.
public static class AtomicFile
{
    public static void WriteAllBytes(string path, byte[] bytes)
    {
        var directory = Path.GetDirectoryName(path)!;
        Directory.CreateDirectory(directory);
        var temporaryPath = Path.Combine(directory, $".{Path.GetFileName(path)}.{Guid.NewGuid():N}.originsync.tmp");
        try
        {
            File.WriteAllBytes(temporaryPath, bytes);
            File.Move(temporaryPath, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    public static void WriteAllText(string path, string text) =>
        // UTF-8 without a BOM: that is what Minecraft, Gson and Java's
        // Properties write, and a BOM would become part of the first key.
        WriteAllBytes(path, new System.Text.UTF8Encoding(false).GetBytes(text));

    /// <summary>The file's last-write time as Unix milliseconds (UTC).</summary>
    public static long LastWriteMs(string path) =>
        new DateTimeOffset(File.GetLastWriteTimeUtc(path)).ToUnixTimeMilliseconds();
}
