namespace OriginLauncher.App.Core.Profiles;

/// <summary>
/// Changes to the profile list, as plain list edits with the rules applied.
/// The UI calls these INSIDE SettingsStore.Update(s => ...) on
/// LauncherSettings.ModProfiles, so every write lands on what is on disk now.
/// Each returns an error sentence (null = done) and leaves the list untouched on error.
/// </summary>
public static class ProfileBook
{
    /// <summary>Adds a new, empty profile. <paramref name="created"/> is it on success.</summary>
    public static string? Create(IList<ModProfile> profiles, string? name, string? version,
        string? serverName, string? serverAddress, out ModProfile? created, Func<string>? newId = null)
    {
        created = null;
        if (!ProfileRules.IsValidVersion(version)) return "Pick a Minecraft version for the profile.";
        if (ProfileRules.ValidateName(name, version!, profiles) is { } nameError) return nameError;
        if (ProfileRules.ValidateServer(serverName, serverAddress) is { } serverError) return serverError;

        created = new ModProfile
        {
            Id = (newId ?? ProfileRules.NewId)(),
            Name = ProfileRules.Clean(name)!,
            Version = version!,
            ServerName = ProfileRules.Clean(serverName),
            ServerAddress = ProfileRules.Clean(serverAddress),
        };
        profiles.Add(created);
        return null;
    }

    /// <summary>Renames a profile and/or changes its server. The version never changes.</summary>
    public static string? Edit(IList<ModProfile> profiles, string id, string? name, string? serverName, string? serverAddress)
    {
        var profile = profiles.FirstOrDefault(p => p.Id == id);
        if (profile == null) return "That profile no longer exists.";
        if (ProfileRules.ValidateName(name, profile.Version, profiles, id) is { } nameError) return nameError;
        if (ProfileRules.ValidateServer(serverName, serverAddress) is { } serverError) return serverError;

        profile.Name = ProfileRules.Clean(name)!;
        profile.ServerName = ProfileRules.Clean(serverName);
        profile.ServerAddress = ProfileRules.Clean(serverAddress);
        return null;
    }

    /// <summary>
    /// Adds a copy of <paramref name="sourceId"/>'s settings under a free name
    /// and a new id. The mods are copied on disk by ProfileFolders, from the
    /// source's folder to the copy's (<paramref name="copy"/> says which).
    /// </summary>
    public static string? Duplicate(IList<ModProfile> profiles, string sourceId, out ModProfile? copy, Func<string>? newId = null)
    {
        copy = null;
        var source = profiles.FirstOrDefault(p => p.Id == sourceId);
        if (source == null) return "That profile no longer exists.";

        copy = new ModProfile
        {
            Id = (newId ?? ProfileRules.NewId)(),
            Name = ProfileRules.UniqueCopyName(source.Name, source.Version, profiles),
            Version = source.Version,
            ServerName = source.ServerName,
            ServerAddress = source.ServerAddress,
            Tint = source.Tint,
            Icon = source.Icon,
        };
        profiles.Add(copy);
        return null;
    }

    /// <summary>Removes the profile (its folder is ProfileFolders' job). False when it wasn't there.</summary>
    public static bool Remove(IList<ModProfile> profiles, string id)
    {
        var index = profiles.ToList().FindIndex(p => p.Id == id);
        if (index < 0) return false;
        profiles.RemoveAt(index);
        return true;
    }
}
