namespace OriginSetup.Engine;

// What the Problem page says when Installer.Run throws: a title, a body the player can act on, and whether "Try
// again" makes sense. Pure, so every wording is unit tested (InstallFailureTests); SetupWindow only shows it.
//
// Any exception out of Installer.Run means the launcher was never moved into place, or the move put the old files
// back (Installer.Commit), so an update that fails leaves the player's current Origin as it was. The title says
// "wasn't updated" then, not "wasn't installed", which read as "my Origin is gone".
public sealed class InstallFailure
{
    private InstallFailure(string title, string body, bool canRetry)
    {
        Title = title;
        Body = body;
        CanRetry = canRetry;
    }

    public string Title { get; }
    public string Body { get; }
    public bool CanRetry { get; }

    public const string DiskFull =
        "The drive is full. Free up some space (Origin needs about 400 MB), then try again.";
    public const string FileInUse =
        "A file couldn't be written. Close anything that might be using Origin's folder, then try again. If it keeps happening, your antivirus may be blocking Origin.";
    public const string Unexpected =
        "Setup hit an unexpected error. Try again. If it keeps happening, use Copy details and send it to us.";

    /// <summary>Null for a cancel (the Ready page says "Install cancelled" instead of a Problem page).</summary>
    /// <param name="updating">An Origin was already installed (update or repair).</param>
    public static InstallFailure? Describe(Exception ex, bool updating)
    {
        var all = ex is AggregateException agg ? agg.Flatten().InnerExceptions.ToList() : new List<Exception> { ex };
        if (all.Any(e => e is OperationCanceledException)) return null;

        var notDone = updating ? "Origin wasn't updated" : "Origin wasn't installed";
        var first = all[0];
        if (first is DownloadException de) return new InstallFailure("Couldn't download Origin", de.Message, true);
        if (first is SetupException se) return new InstallFailure(notDone, se.Message, true);
        // Only a local --source zip gets here; a damaged download is a DownloadException.
        if (first is InvalidDataException || first is EndOfStreamException)
            return new InstallFailure("This package is damaged", "The launcher package Setup was given is incomplete or corrupted.", false);
        if (IsDiskFull(first)) return new InstallFailure(notDone, DiskFull, true);
        if (first is UnauthorizedAccessException || first is IOException) return new InstallFailure(notDone, FileInUse, true);
        return new InstallFailure("Something went wrong", Unexpected, true);
    }

    /// <summary>Windows' "disk full" errors (ERROR_HANDLE_DISK_FULL, ERROR_DISK_FULL), anywhere in the chain.</summary>
    public static bool IsDiskFull(Exception? ex)
    {
        for (; ex != null; ex = ex.InnerException)
        {
            int code = ex.HResult & 0xFFFF;
            if (ex is IOException && (code == 0x27 || code == 0x70)) return true;
        }
        return false;
    }
}
