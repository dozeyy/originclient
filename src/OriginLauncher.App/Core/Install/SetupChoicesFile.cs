using System.IO;
using System.Text.Json;

namespace OriginLauncher.App.Core.Install;

// The file shell of the installer hand-off (see SetupChoices): read the file,
// hand what it says to the caller, and ALWAYS delete it afterwards. Split out
// from SetupChoices (which needs the settings store) so the shell is unit-tested
// on a temp folder (SetupHandoffTests) and never touches the real data folder.
public static class SetupChoicesFile
{
    public static void Consume(string path, Action<SetupChoicesData> apply)
    {
        if (!File.Exists(path)) return;
        try
        {
            var choices = SetupChoicesData.Parse(File.ReadAllText(path));
            if (choices != null) apply(choices);
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            // A bad hand-off only loses the installer's suggestions — the
            // launcher's own defaults still apply. Never block startup on it.
        }
        finally
        {
            try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }
}
