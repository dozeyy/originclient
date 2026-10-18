using System.IO;
using System.Text.Json;

namespace OriginLauncher.App.Core.Install;

// Hand-off from the installer: choices the player made on its Customize page
// (memory for Minecraft, default version, Settings Sync). The installer can't write
// settings.json itself — SettingsStore.Update is the ONLY writer of settings
// — so it drops this small file and the launcher applies it on its next
// start, then deletes it. Format written by src/OriginSetup/Engine/Handoff.cs.
public static class SetupChoices
{
    public const string FileName = "setup-choices.json";

    public static void ApplyIfPresent()
    {
        var path = Path.Combine(OriginPaths.Root, FileName);
        if (!File.Exists(path)) return;
        try
        {
            var choices = SetupChoicesData.Parse(File.ReadAllText(path));
            if (choices != null)
            {
                var maxRam = RamAdvice.MaximumMb(SystemInfo.GetTotalPhysicalMemoryMb());
                SettingsStore.Update(s => choices.ApplyTo(s, maxRam, Versions.VersionCatalog.IsSupported));
                // Same as turning the switch on in Settings: build the sync
                // store from every instance. Off the startup path, and
                // SettingsSync.Seed logs and swallows its own errors, so this
                // can never fail or delay a launch.
                if (choices.SettingsSyncEnabled == true)
                    _ = Task.Run(SettingsSync.Seed);
            }
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
