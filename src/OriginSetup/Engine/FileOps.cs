using System.Threading;

namespace OriginSetup.Engine;

// File moves/deletes that tolerate the brief locks antivirus scanners and
// Windows Search put on freshly written files.
public static class FileOps
{
    public static void Retry(Action action, int attempts = 20)
    {
        for (int i = 1; ; i++)
        {
            try { action(); return; }
            catch (Exception ex) when ((ex is IOException || ex is UnauthorizedAccessException) && i < attempts)
            {
                Thread.Sleep(150);
            }
        }
    }

    /// <summary>Moves every file under <paramref name="from"/> into the same place under <paramref name="to"/>, replacing.</summary>
    public static void MoveTree(string from, string to)
    {
        foreach (var file in Directory.EnumerateFiles(from, "*", SearchOption.AllDirectories).ToList())
        {
            var dest = Path.Combine(to, file.Substring(from.Length).TrimStart(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            Retry(() =>
            {
                if (File.Exists(dest))
                {
                    File.SetAttributes(dest, FileAttributes.Normal);
                    File.Delete(dest);
                }
                File.Move(file, dest);
            });
        }
    }

    public static void DeleteTree(string dir)
    {
        if (!Directory.Exists(dir)) return;
        Retry(() =>
        {
            foreach (var f in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories))
                File.SetAttributes(f, FileAttributes.Normal);
            Directory.Delete(dir, recursive: true);
        });
    }

    /// <summary>Best effort: false if anything could not be removed (e.g. Minecraft still running).</summary>
    public static bool TryDeleteTree(string dir)
    {
        try { DeleteTree(dir); return true; }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
        {
            SetupLog.Write($"could not fully remove {dir}: {ex.Message}");
            return false;
        }
    }

    public static void DeleteIfEmpty(string dir)
    {
        try { if (Directory.Exists(dir) && !Directory.EnumerateFileSystemEntries(dir).Any()) Directory.Delete(dir); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
