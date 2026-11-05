using System.IO;
using System.Text.Json;

namespace OriginLauncher.App.Core.Install;

// The file shell of the installer hand-off (see SetupChoices): read the file, hand what it says to the caller, and
// delete it once it is used up. Split out from SetupChoices (which needs the settings store) so the shell is
// unit-tested on a temp folder (SetupHandoffTests) and never touches the real data folder.
//  - Applied, unreadable as JSON, or an unexpected failure: deleted (a bad file must not re-apply every start).
//  - The file or settings.json busy right now (IOException / UnauthorizedAccessException: an antivirus or backup tool
//    scanning the freshly installed files, SettingsStore refusing a busy settings.json): KEPT for the next start
//    (UPG-6). Deleting it lost the memory, version and Settings Sync the player picked in Setup, for good.
public static class SetupChoicesFile
{
    public static void Consume(string path, Action<SetupChoicesData> apply)
    {
        if (!File.Exists(path)) return;
        var keep = false;
        try
        {
            var choices = SetupChoicesData.Parse(File.ReadAllText(path));
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
            if (!keep)
            {
                try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
        }
    }
}
