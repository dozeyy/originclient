using OriginLauncher.App.Core.Social;

namespace OriginLauncher.App.Core.Profiles;

/// <summary>
/// The rules for mod profiles that need no disk and no UI: ids, names, server
/// addresses, which profile a launch uses. Pure, so it is unit tested on Linux
/// (src/OriginLauncher.Tests/ModProfileTests.cs).
/// </summary>
public static class ProfileRules
{
    public const int MaxNameLength = 32;
    public const int MaxServerNameLength = 32;

    public static string NewId() => Guid.NewGuid().ToString("N");

    /// <summary>
    /// True for an id this code made. Checked before an id is ever used as a
    /// folder name, so a hand-edited settings.json can't point a profile at
    /// "..\\..\\somewhere".
    /// </summary>
    public static bool IsValidId(string? id) =>
        id is { Length: 32 } && id.All(c => c is (>= '0' and <= '9') or (>= 'a' and <= 'f'));

    /// <summary>A version string safe to use as a folder name (what the version catalog holds).</summary>
    public static bool IsValidVersion(string? version) =>
        version is { Length: > 0 and <= 32 }
        && char.IsAsciiLetterOrDigit(version[0]) && !version.Contains("..", StringComparison.Ordinal)
        && version.All(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '-' or '_');

    /// <summary>A stored profile this launcher can safely use.</summary>
    public static bool IsUsable(ModProfile? profile) =>
        profile != null && IsValidId(profile.Id) && IsValidVersion(profile.Version)
        && !string.IsNullOrWhiteSpace(profile.Name);

    /// <summary>Trimmed text, or null when nothing is left.</summary>
    public static string? Clean(string? text)
    {
        var trimmed = text?.Trim();
        return string.IsNullOrEmpty(trimmed) ? null : trimmed;
    }

    /// <summary>
    /// Null when <paramref name="name"/> is fine for a profile of
    /// <paramref name="version"/>; otherwise the sentence to show. Names are
    /// unique per version (case-insensitive); the profile being edited
    /// (<paramref name="exceptId"/>) doesn't clash with itself.
    /// </summary>
    public static string? ValidateName(string? name, string version, IEnumerable<ModProfile> existing, string? exceptId = null)
    {
        var clean = Clean(name);
        if (clean == null) return "Give the profile a name.";
        if (clean.Length > MaxNameLength) return $"Keep the name to {MaxNameLength} characters or fewer.";
        if (clean.Any(char.IsControl)) return "That name has characters that can't be used.";
        var taken = existing.Any(p => p.Id != exceptId
                                      && string.Equals(p.Version, version, StringComparison.Ordinal)
                                      && string.Equals(p.Name.Trim(), clean, StringComparison.OrdinalIgnoreCase));
        return taken ? $"You already have a {version} profile called \"{clean}\"." : null;
    }

    /// <summary>
    /// The server is optional, but a name without an address is useless, and an
    /// address must be one the game can be handed (JoinArguments.TryParseServer).
    /// </summary>
    public static string? ValidateServer(string? serverName, string? serverAddress)
    {
        var name = Clean(serverName);
        var address = Clean(serverAddress);
        if (name != null && name.Length > MaxServerNameLength)
            return $"Keep the server name to {MaxServerNameLength} characters or fewer.";
        if (name != null && name.Any(char.IsControl)) return "That server name has characters that can't be used.";
        if (address == null) return name == null ? null : "Add the server's address too (like play.example.net).";
        return JoinArguments.TryParseServer(address, out _, out _)
            ? null
            : "That doesn't look like a server address. Use play.example.net or play.example.net:25566.";
    }

    /// <summary>"Join Hypixel" — or "Join play.example.net" when the profile has no server name.</summary>
    public static string JoinLabel(ModProfile profile)
    {
        var name = ServerLabel(profile);
        return name == null ? "Join server" : "Join " + name;
    }

    /// <summary>The server's name ("Hypixel"), else its host, else null (no usable server).</summary>
    public static string? ServerLabel(ModProfile profile)
    {
        var name = Clean(profile.ServerName);
        if (name == null && JoinArguments.TryParseServer(profile.ServerAddress, out var host, out _))
            name = host;
        return name;
    }

    /// <summary>"Hypixel PvP copy", then "Hypixel PvP copy 2"... the first one free for the version.</summary>
    public static string UniqueCopyName(string name, string version, IEnumerable<ModProfile> existing)
    {
        var all = existing.ToList();
        var baseName = name.Trim();
        for (var n = 1; ; n++)
        {
            var suffix = n == 1 ? " copy" : $" copy {n}";
            var room = Math.Max(1, MaxNameLength - suffix.Length);
            var candidate = (baseName.Length > room ? baseName[..room].TrimEnd() : baseName) + suffix;
            if (ValidateName(candidate, version, all) == null) return candidate;
        }
    }

    /// <summary>
    /// The profile Home is using for <paramref name="version"/>: the selected
    /// one, but only when it belongs to that version (a profile is for ONE
    /// version — on any other the player's normal mods apply). Null = none.
    /// </summary>
    public static ModProfile? Active(IEnumerable<ModProfile> profiles, string? selectedId, string? version)
    {
        if (selectedId == null || version == null) return null;
        var profile = profiles.FirstOrDefault(p => p.Id == selectedId);
        return IsUsable(profile) && string.Equals(profile!.Version, version, StringComparison.Ordinal) ? profile : null;
    }

    /// <summary>
    /// The profile a LAUNCH of <paramref name="version"/> loads mods from.
    /// "Personal mods" off means no player mods at all (Origin-only), so no
    /// profile either — the crash window's Origin-only retry relies on that.
    /// </summary>
    public static ModProfile? ForLaunch(IEnumerable<ModProfile> profiles, string? selectedId, string version, bool externalMods) =>
        externalMods ? Active(profiles, selectedId, version) : null;
}
