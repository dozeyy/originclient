using System.Text;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Launch;

// The "Copy report" text: what a player pastes into Discord or a support thread.
// Pure (strings in, string out). Everything passes through Scrub, so tokens,
// emails, server addresses and the Windows user name never leave the PC by accident.
// Origin never sends it anywhere: it only goes to the clipboard when the player presses Copy report.
public static class CrashReportBundle
{
    public const int MaxLogLines = 200;
    public const int MaxCrashReportLines = 60;
    public const int MaxLineChars = 300;
    public const int MaxTotalChars = 40_000;

    private const string Redacted = "<redacted>";

    private static Regex Rx(string pattern) =>
        new(pattern, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);

    // Game launch arguments that carry identity or where the player plays.
    private static readonly Regex LaunchArg = Rx(@"(--(?:accessToken|session|uuid|xuid|clientId|username|userProperties|server|quickPlayMultiplayer)[ =]+)(\S+)");
    // "accessToken=abc", "Authorization: abc", "refresh_token": "abc", "token:abc" ...
    private static readonly Regex KeyedSecret = Rx(@"(access[_ -]?token|refresh[_ -]?token|id[_ -]?token|session[_ -]?id|authorization|client[_ -]?secret|password|token)([""']?\s*[:=]\s*[""']?)([^\s""',;}]+)");
    private static readonly Regex Bearer = Rx(@"Bearer\s+[A-Za-z0-9._\-~+/]+=*");
    private static readonly Regex Jwt = Rx(@"eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]*");
    private static readonly Regex Email = Rx(@"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}");
    private static readonly Regex UserPath = Rx(@"([A-Za-z]:[\\/]+Users[\\/]+)([^\\/\s""'<>|:*?]+)");
    private static readonly Regex UnixUserPath = Rx(@"(/(?:Users|home)/)([^/\s""'<>|:*?]+)");
    private static readonly Regex SettingUser = Rx(@"(Setting user:\s*)(\S+)");
    // "UUID of player Steve is 069a79f4-..." (the server side of a singleplayer world logs the player's name and id).
    private static readonly Regex PlayerUuid = Rx(@"(UUID of player\s+)(\S+)(\s+is\s+)(\S+)");
    // Any IPv4 address except loopback and the any-address: a server the player joined, or their own address.
    // Octets are 0-255 and no digit or dot may touch either end, so four-part versions like 11.15.1.2318 stay. A
    // number glued to a word, a brace or a dash is a version, not an address: "FML{8.0.99.99}", "v1.2.3.4", "1.2.3.4-beta".
    private static readonly Regex Ipv4 = new(
        @"(?<![\w.{-])(?!127\.)(?!0\.0\.0\.0(?![\d.]))(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?![\w.}-])",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);

    /// <summary>
    /// Removes secrets from text. <paramref name="knownNames"/> (the Windows user name, the player name)
    /// are replaced wherever they appear, whole-word, when they are at least 3 characters.
    /// </summary>
    public static string Scrub(string text, IEnumerable<string>? knownNames = null)
    {
        if (string.IsNullOrEmpty(text)) return text ?? "";
        var s = text;
        // Known names first: a later pattern may rewrite part of one ("C:\Users\Will Henry" -> "<user> Henry").
        if (knownNames != null)
            foreach (var name in knownNames.Where(n => n is { Length: >= 3 }).Distinct(StringComparer.OrdinalIgnoreCase)
                         .OrderByDescending(n => n.Length))
                s = Regex.Replace(s, $@"(?<![A-Za-z0-9_]){Regex.Escape(name)}(?![A-Za-z0-9_])", "<user>",
                    RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        s = Jwt.Replace(s, Redacted);
        s = AuthorizationHeader.Replace(s, "$1" + Redacted);
        s = Bearer.Replace(s, "Bearer " + Redacted);
        s = LaunchArg.Replace(s, "$1" + Redacted);
        s = KeyedSecret.Replace(s, "$1$2" + Redacted);
        s = SettingUser.Replace(s, "$1<player>");
        s = PlayerUuid.Replace(s, "$1<player>$3" + Redacted);
        s = ServerHost.Replace(s, "$1<server>$3");
        s = Uuid.Replace(s, "<uuid>");
        s = Ipv4.Replace(s, "<ip>");
        s = Ipv6Candidate.Replace(s, m => IsIpv6(m.Value) ? "<ip>" : m.Value);
        s = Email.Replace(s, "<email>");
        s = UserPathWithSpaces.Replace(s, "$1<user>");
        s = UserPath.Replace(s, "$1<user>");
        s = UnixUserPath.Replace(s, "$1<user>");
        return s;
    }

    // "Authorization: XBL3.0 x=<uhs>;<token>": everything after the header name is the secret.
    private static readonly Regex AuthorizationHeader = Rx(@"(authorization[""']?\s*[:=]\s*)[^\r\n]+");
    // Minecraft logs the server it joins ("Connecting to mc.example.net, 25565"), and crash reports name it.
    private static readonly Regex ServerHost = Rx(@"(Connecting to\s+|Server (?:IP|address):\s*)([^\s,]+)(,?)");
    // Account and player ids (dashed, or the 32-hex form in JSON and account folders): public lookups turn one back into a name.
    private static readonly Regex Uuid = Rx(@"(?<![0-9a-f])(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32})(?![0-9a-f])");
    // A user folder whose name has spaces ("C:\Users\Will Henry\..."): only when a separator follows, so plain text
    // after "C:\Users\x" is not swallowed. The no-space form below catches the rest.
    private static readonly Regex UserPathWithSpaces = Rx(@"([A-Za-z]:[\\/]+Users[\\/]+)(?!<user>)([^\\/""'<>|:*?\r\n]+)(?=[\\/])");
    // IPv6 candidates are checked by the real parser, so timestamps ("12:00:01") and "Foo::bar" stay.
    private static readonly Regex Ipv6Candidate = Rx(@"(?<![\w:.])[0-9a-f:]{3,39}(?![\w:.])");

    private static bool IsIpv6(string s) =>
        s.Contains(':') && s.Count(c => c == ':') >= 2 && s.Any(char.IsAsciiHexDigit)
        && System.Net.IPAddress.TryParse(s, out var ip)
        && ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6
        && !System.Net.IPAddress.IsLoopback(ip) && !ip.Equals(System.Net.IPAddress.IPv6Any);

    public sealed record Input(
        string LauncherVersion,
        string MinecraftVersion,
        int RamMb,
        int TotalRamMb,
        int ExitCode,
        CrashVerdict Verdict,
        string? LogText,
        string? CrashReportText,
        IReadOnlyCollection<string>? KnownNames = null);

    public static string Build(Input input)
    {
        var sb = new StringBuilder();
        sb.AppendLine("Origin Launcher - crash report");
        sb.AppendLine($"Launcher     : {input.LauncherVersion}");
        sb.AppendLine($"Minecraft    : {input.MinecraftVersion}");
        sb.AppendLine($"Java         : {JavaVersionOf(input.CrashReportText ?? input.LogText)}");
        sb.AppendLine($"Memory       : {input.RamMb} MB given of {input.TotalRamMb} MB installed");
        sb.AppendLine($"Exit code    : {input.ExitCode}");
        var f = input.Verdict.Finding;
        sb.AppendLine($"Looks like   : {f.Headline} ({f.Cause})");
        sb.AppendLine($"Likely cause : {f.LikelyCause} [{f.Confidence.Word()}, rule {f.Rule}]");
        if (f.Culprits.Count > 0)
            sb.AppendLine($"Mods named   : {string.Join(", ", f.Culprits.Select(c => c.JarFileName != null ? $"{c.ModId} ({c.JarFileName})" : c.ModId))}");
        foreach (var line in f.TechnicalLines.Take(6)) sb.AppendLine($"Evidence     : {Clip(line)}");
        foreach (var other in input.Verdict.Others.Take(4))
            sb.AppendLine($"Also seen    : {other.Headline} [{other.Confidence.Word()}, rule {other.Rule}]");

        sb.AppendLine().AppendLine($"----- last {MaxLogLines} log lines -----");
        sb.AppendLine(Tail(input.LogText, MaxLogLines));
        if (!string.IsNullOrWhiteSpace(input.CrashReportText))
        {
            sb.AppendLine().AppendLine("----- crash report (start) -----");
            sb.AppendLine(Head(input.CrashReportText, MaxCrashReportLines));
        }

        var scrubbed = Scrub(sb.ToString(), input.KnownNames);
        return scrubbed.Length <= MaxTotalChars ? scrubbed : scrubbed[..MaxTotalChars] + "\n[trimmed]";
    }

    // The crash report's "Java Version: 21.0.2, Microsoft" line, or the JVM banner in the log.
    public static string JavaVersionOf(string? text)
    {
        if (string.IsNullOrEmpty(text)) return "unknown";
        var m = Regex.Match(text, @"Java Version:\s*([^\r\n]+)", RegexOptions.IgnoreCase);
        if (m.Success) return m.Groups[1].Value.Trim();
        m = Regex.Match(text, @"(?:Java|JRE) version:?\s*([0-9][^\r\n]*)", RegexOptions.IgnoreCase);
        return m.Success ? m.Groups[1].Value.Trim() : "unknown";
    }

    private static string Tail(string? text, int lines)
    {
        if (string.IsNullOrWhiteSpace(text)) return "(no log was captured)";
        var all = text.Split('\n');
        return string.Join("\n", all.Skip(Math.Max(0, all.Length - lines)).Select(Clip));
    }

    private static string Head(string text, int lines) =>
        string.Join("\n", text.Split('\n').Take(lines).Select(Clip));

    private static string Clip(string line)
    {
        line = line.TrimEnd('\r');
        return line.Length <= MaxLineChars ? line : line[..MaxLineChars] + " ...";
    }
}
