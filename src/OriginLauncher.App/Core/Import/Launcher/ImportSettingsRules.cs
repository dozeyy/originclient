using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.Core.Import.Launcher;

// The settings change an import makes, as plain edits of a LauncherSettings. The glue runs
// each of these inside ONE SettingsStore.Update, so apply and revert are each a single atomic
// write against what is on disk right now. PlayWithExternalMods is never touched.
//
// A single import carries one profile (AddProfile); an "import everything" run carries one per Minecraft
// version (AddProfiles) in the same delta, so all of them land - and all of them go on a rollback - in that
// one write. A journal written before AddProfiles existed only has AddProfile and still reverts.
public static class ImportSettingsRules
{
    /// <summary>
    /// Done's Play on a bulk import (ImportLogic.DoneAction): select the one profile it made, and its version, so the game
    /// starts with the imported mods. Only a profile that still exists on that version (nothing if it was removed since).
    /// Returns whether the selection changed. Run inside SettingsStore.Update.
    /// </summary>
    public static bool SelectImported(LauncherSettings s, string profileId, string version)
    {
        if (!s.ModProfiles.Any(p => p.Id == profileId && p.Version == version)) return false;
        s.SelectedProfileId = profileId;
        s.SelectedVersion = version;
        return true;
    }

    /// <summary>Adds the new profile(s) (each once), selects the target version and the profile.</summary>
    public static void Apply(LauncherSettings s, SettingsDelta delta)
    {
        foreach (var profile in ProfilesOf(delta))
            if (s.ModProfiles.All(p => p.Id != profile.Id))
                s.ModProfiles.Add(new ModProfile
                {
                    Id = profile.Id, Name = profile.Name, Version = profile.Version,
                    ServerName = profile.ServerName, ServerAddress = profile.ServerAddress,
                });
        if (delta.SetSelectedVersion != null) s.SelectedVersion = delta.SetSelectedVersion;
        if (delta.SetSelectedProfileId != null) s.SelectedProfileId = delta.SetSelectedProfileId;
    }

    /// <summary>
    /// Puts the settings back: the profile(s) go, and a selection goes back to what it was before — but
    /// only when it is still what the import set, so a choice the player made since is never overwritten.
    /// Safe when <see cref="Apply"/> never ran (nothing matches, nothing changes).
    /// </summary>
    public static void Revert(LauncherSettings s, SettingsDelta delta)
    {
        foreach (var profile in ProfilesOf(delta))
            for (var i = s.ModProfiles.Count - 1; i >= 0; i--)
                if (s.ModProfiles[i].Id == profile.Id) s.ModProfiles.RemoveAt(i);

        if (delta.SetSelectedProfileId != null && s.SelectedProfileId == delta.SetSelectedProfileId)
            s.SelectedProfileId = delta.PreviousSelectedProfileId;

        if (delta.SetSelectedVersion != null && s.SelectedVersion == delta.SetSelectedVersion)
            s.SelectedVersion = delta.PreviousSelectedVersion;
    }

    // Every profile the delta adds: the single one (older journals) and the list (bulk), without repeats.
    private static IEnumerable<ModProfile> ProfilesOf(SettingsDelta delta)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        if (delta.AddProfile is { } one && seen.Add(one.Id)) yield return one;
        if (delta.AddProfiles != null)
            foreach (var profile in delta.AddProfiles)
                if (profile != null && seen.Add(profile.Id)) yield return profile;
    }
}
