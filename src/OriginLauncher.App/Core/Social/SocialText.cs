using System.Globalization;

namespace OriginLauncher.App.Core.Social;

/// <summary>Every player-facing social sentence that depends on data.</summary>
public static class SocialText
{
    /// <summary>"1,284 playing Origin" — thousands separators, the same on every PC.</summary>
    public static string PlayersOnline(int count) =>
        $"{Math.Max(0, count).ToString("N0", CultureInfo.InvariantCulture)} playing Origin";

    /// <summary>
    /// A friend's one-line status: "Playing on hypixel.net · 1.21.1",
    /// "Playing singleplayer · 1.20.1", "In game · 1.21.1", "In the launcher",
    /// "Offline". The version is left off when the friend's client didn't send one.
    /// </summary>
    public static string StatusLine(FriendInfo friend)
    {
        var text = ActivityLine(friend);
        if (!friend.Online || friend.Client == "launcher" || string.IsNullOrWhiteSpace(friend.Version)) return text;
        return $"{text} · {friend.Version}";
    }

    /// <summary>
    /// The same line without the Minecraft version ("Playing on hypixel.net"): what a
    /// narrow list row shows, with the version left to a tooltip or a chat header.
    /// </summary>
    public static string ActivityLine(FriendInfo friend)
    {
        if (!friend.Online) return "Offline";
        if (friend.Client == "launcher") return "In the launcher";

        return friend.Activity switch
        {
            "server" when !string.IsNullOrWhiteSpace(friend.Server) => $"Playing on {DisplayServer(friend.Server)}",
            "singleplayer" => "Playing singleplayer",
            _ => "In game",
        };
    }

    /// <summary>"hypixel.net:25565" reads as "hypixel.net"; other ports stay.</summary>
    public static string DisplayServer(string server)
    {
        var s = server.Trim();
        return s.EndsWith(":25565", StringComparison.Ordinal) ? s[..^6] : s;
    }

    /// <summary>The first letter of a name, for the drawer's avatar blocks.</summary>
    public static string Initial(string? name) =>
        string.IsNullOrEmpty(name) ? "?" : name[..1].ToUpperInvariant();

    /// <summary>A Minecraft username as Mojang allows it (3-16 of A-Z a-z 0-9 _).</summary>
    public static bool IsValidUsername(string? name) =>
        name is { Length: >= 3 and <= 16 } && name.All(c => char.IsAsciiLetterOrDigit(c) || c == '_');

    public const int MaxMessageLength = 256;
}
