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

    /// <summary>
    /// Moves every file under <paramref name="from"/> into the same place under <paramref name="to"/>,
    /// replacing. All or nothing: each file it replaces is first moved into <paramref name="backup"/>
    /// (same drive, so a rename), and if any move fails every new file is taken out again and every
    /// replaced one put back before the exception is rethrown. A file that can't be put back stays
    /// in <paramref name="backup"/> (logged), which the caller then must not delete.
    /// </summary>
    public static void MoveTree(string from, string to, string backup)
    {
        var done = new List<(string Dest, string? Saved, bool Placed)>();
        try
        {
            foreach (var file in Directory.EnumerateFiles(from, "*", SearchOption.AllDirectories).ToList())
            {
                var rel = file.Substring(from.Length).TrimStart(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                var dest = Path.Combine(to, rel);
                Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                string? saved = null;
                if (File.Exists(dest))
                {
                    saved = Path.Combine(backup, rel);
                    Directory.CreateDirectory(Path.GetDirectoryName(saved)!);
                    Retry(() =>
                    {
                        File.SetAttributes(dest, FileAttributes.Normal);
                        File.Move(dest, saved);
                    });
                }
                done.Add((dest, saved, false));
                Retry(() => File.Move(file, dest));
                done[done.Count - 1] = (dest, saved, true);
            }
        }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
        {
            SetupLog.Write($"install move failed ({ex.Message}); putting the previous files back");
            for (int i = done.Count - 1; i >= 0; i--)
            {
                var (dest, saved, placed) = done[i];
                try
                {
                    if (placed) Retry(() => { if (File.Exists(dest)) File.Delete(dest); }, attempts: 5);
                    if (saved != null) Retry(() => File.Move(saved, dest), attempts: 5);
                }
                catch (Exception undo) when (undo is IOException || undo is UnauthorizedAccessException)
                {
                    SetupLog.Write($"could not put back {dest}: {undo.Message}" + (saved != null ? $" (the old file is kept in {saved})" : ""));
                }
            }
            throw;
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
