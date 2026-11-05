using System.IO;
using System.Text.Json;

namespace OriginLauncher.App.Core.Install;

// The file shell of the installer hand-off (see SetupChoices): read the file, hand what it says to the caller, and
// make sure it is applied at most once. Split out from SetupChoices (which needs the settings store) so the shell is
// unit-tested on a temp folder (SetupHandoffTests) and never touches the real data folder.
//  - Applied, unreadable as JSON, or an unexpected failure: removed (a bad file must not re-apply every start). If it
//    can't be deleted it is emptied to "{}", which applies nothing, so an applied file never applies twice.
//  - The file or settings.json busy right now (IOException / UnauthorizedAccessException: an antivirus or backup tool
//    scanning the freshly installed files, SettingsStore refusing a busy settings.json): set aside as
//    setup-choices.json.retry for the next start (UPG-6), instead of losing the memory, version and Settings Sync the
//    player picked in Setup for good. The next start applies it only if settings.json has not been written since it
//    was set aside: once the player (or the launcher) changed settings, those win and the retry is dropped.
public static class SetupChoicesFile
{
    public const string RetrySuffix = ".retry";

    public static void Consume(string path, string settingsPath, Action<SetupChoicesData> apply)
    {
        var retry = path + RetrySuffix;
        if (File.Exists(path))
        {
            Remove(retry); // a fresh hand-off from Setup replaces any older one still waiting
            Try(path, retry, apply);
            return;
        }
        if (!File.Exists(retry)) return;
        if (WrittenSince(settingsPath, retry))
        {
            Remove(retry); // settings changed after it was set aside: the newer settings win
            return;
        }
        Try(retry, retry, apply);
    }

    private static void Try(string file, string retry, Action<SetupChoicesData> apply)
    {
        var keep = false;
        try
        {
            var choices = SetupChoicesData.Parse(File.ReadAllText(file));
            if (choices != null) apply(choices);
        }
        catch (JsonException)
        {
            // A bad hand-off only loses the installer's suggestions; the launcher's own defaults still apply.
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Busy for a moment: never block startup on it, try again at the next start.
            keep = true;
        }
        finally
        {
            if (keep) SetAside(file, retry);
            else Remove(file);
        }
    }

    // Kept for the next start, stamped now: settings.json written after this moment means it is out of date.
    private static void SetAside(string file, string retry)
    {
        try
        {
            if (!string.Equals(file, retry, StringComparison.Ordinal)) File.Move(file, retry, overwrite: true);
            File.SetLastWriteTimeUtc(retry, DateTime.UtcNow);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Couldn't be set aside: it stays where it is and is tried again as it is.
        }
    }

    private static bool WrittenSince(string settingsPath, string retry)
    {
        try { return File.Exists(settingsPath) && File.GetLastWriteTimeUtc(settingsPath) > File.GetLastWriteTimeUtc(retry); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return true; }
    }

    // Deleted, or (when something holds it) emptied so it can't apply again.
    private static void Remove(string file)
    {
        try { File.Delete(file); return; }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        try { File.WriteAllText(file, "{}"); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
    }
}
