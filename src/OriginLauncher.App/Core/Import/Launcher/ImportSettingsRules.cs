using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.Core.Import.Launcher;

// The settings change an import makes, as plain edits of a LauncherSettings. The glue runs
// each of these inside ONE SettingsStore.Update, so apply and revert are each a single atomic
// write against what is on disk right now. PlayWithExternalMods is never touched.
public static class ImportSettingsRules
{
    /// <summary>Adds the new profile (once), selects the target version and the profile.</summary>
    public static void Apply(LauncherSettings s, SettingsDelta delta)
    {
        if (delta.AddProfile is { } profile && s.ModProfiles.All(p => p.Id != profile.Id))
            s.ModProfiles.Add(new ModProfile
            {
                Id = profile.Id, Name = profile.Name, Version = profile.Version,
                ServerName = profile.ServerName, ServerAddress = profile.ServerAddress,
            });
        if (delta.SetSelectedVersion != null) s.SelectedVersion = delta.SetSelectedVersion;
        if (delta.SetSelectedProfileId != null) s.SelectedProfileId = delta.SetSelectedProfileId;
    }

    /// <summary>
    /// Puts the settings back: the profile goes, and a selection goes back to what it was before — but
    /// only when it is still what the import set, so a choice the player made since is never overwritten.
    /// Safe when <see cref="Apply"/> never ran (nothing matches, nothing changes).
    /// </summary>
    public static void Revert(LauncherSettings s, SettingsDelta delta)
    {
        if (delta.AddProfile is { } profile)
            for (var i = s.ModProfiles.Count - 1; i >= 0; i--)
                if (s.ModProfiles[i].Id == profile.Id) s.ModProfiles.RemoveAt(i);

        if (delta.SetSelectedProfileId != null && s.SelectedProfileId == delta.SetSelectedProfileId)
            s.SelectedProfileId = delta.PreviousSelectedProfileId;

        if (delta.SetSelectedVersion != null && s.SelectedVersion == delta.SetSelectedVersion)
            s.SelectedVersion = delta.PreviousSelectedVersion;
    }
}
