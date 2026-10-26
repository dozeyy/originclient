using System.Globalization;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Social;

/// <summary>Why "Join" can't go ahead (None = it can).</summary>
public enum JoinBlock { None, NoServer, UnknownVersion, UnsupportedVersion }

/// <summary>
/// Launching straight into a friend's server. Minecraft 1.20 added Quick Play
/// (`--quickPlayMultiplayer host[:port]`) and dropped the old `--server` /
/// `--port` pair; every older version Origin ships still has that pair. The
/// arguments are added as extra game arguments on the normal launch path.
/// </summary>
public static class JoinArguments
{
    public const int DefaultPort = 25565;
    public const string QuickPlaySince = "1.20";

    public static bool SupportsQuickPlay(string version) => McVersion.AtLeast(version, QuickPlaySince);

    /// <summary>
    /// The game arguments that open <paramref name="server"/> on start, or an
    /// empty list when the address isn't one we'd hand to the game. The default
    /// port is left off on purpose: Minecraft only looks up a server's SRV
    /// record when no port (or 25565) is given, and plenty of networks use one.
    /// </summary>
    public static IReadOnlyList<string> Build(string version, string server)
    {
        if (!TryParseServer(server, out var host, out var port)) return [];

        if (SupportsQuickPlay(version))
            return ["--quickPlayMultiplayer", FormatAddress(host, port)];

        return port == DefaultPort
            ? ["--server", host]
            : ["--server", host, "--port", port.ToString(CultureInfo.InvariantCulture)];
    }

    /// <summary>Can the launcher join this friend at all, and if not, why.</summary>
    public static JoinBlock Check(string? version, string? server, Func<string, bool> isSupported)
    {
        if (!TryParseServer(server, out _, out _)) return JoinBlock.NoServer;
        if (string.IsNullOrWhiteSpace(version)) return JoinBlock.UnknownVersion;
        return isSupported(version) ? JoinBlock.None : JoinBlock.UnsupportedVersion;
    }

    public static string Explain(JoinBlock block, string who, string? version) => block switch
    {
        JoinBlock.NoServer => $"{who} isn't on a server right now.",
        JoinBlock.UnknownVersion => $"Origin can't tell which Minecraft version {who} is playing, so it can't join from here.",
        JoinBlock.UnsupportedVersion => $"{who} is playing Minecraft {version}, which Origin doesn't support yet - so you can't join from the launcher.",
        _ => "",
    };

    /// <summary>
    /// "host", "host:port", "[ipv6]:port" or a bare IPv6 address. Hosts are
    /// limited to the characters a real address uses, so nothing from the
    /// network can ever smuggle an extra argument onto the game's command line.
    /// </summary>
    public static bool TryParseServer(string? server, out string host, out int port)
    {
        host = "";
        port = DefaultPort;
        if (string.IsNullOrWhiteSpace(server)) return false;
        var s = server.Trim();

        if (s.StartsWith('['))
        {
            var end = s.IndexOf(']');
            if (end < 2) return false;
            host = s[1..end];
            var rest = s[(end + 1)..];
            if (rest.Length > 0 && (!rest.StartsWith(':') || !TryPort(rest[1..], out port))) return false;
        }
        else
        {
            var first = s.IndexOf(':');
            var last = s.LastIndexOf(':');
            if (first >= 0 && first == last)
            {
                host = s[..first];
                if (!TryPort(s[(first + 1)..], out port)) return false;
            }
            else
            {
                host = s; // no port, or a bare IPv6 address
            }
        }

        return IsSafeHost(host);
    }

    private static bool TryPort(string text, out int port) =>
        int.TryParse(text, NumberStyles.None, CultureInfo.InvariantCulture, out port) && port is >= 1 and <= 65535;

    private static bool IsSafeHost(string host) =>
        host.Length is > 0 and <= 253
        && host[0] != '-'
        && host.All(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '-' or '_' or ':');

    private static string FormatAddress(string host, int port)
    {
        var h = host.Contains(':') ? $"[{host}]" : host;
        return port == DefaultPort ? h : $"{h}:{port.ToString(CultureInfo.InvariantCulture)}";
    }
}
