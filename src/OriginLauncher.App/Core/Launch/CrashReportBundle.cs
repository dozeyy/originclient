using System.Text;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Launch;

// The "Copy report" text: what a player pastes into Discord or a support thread.
// Pure (strings in, string out). Everything passes through Scrub, so tokens,
// emails and the Windows user name never leave the PC by accident.
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

    /// <summary>
    /// Removes secrets from text. <paramref name="knownNames"/> (the Windows user name, the player name)
    /// are replaced wherever they appear, whole-word, when they are at least 3 characters.
    /// </summary>
    public static string Scrub(string text, IEnumerable<string>? knownNames = null)
    {
        if (string.IsNullOrEmpty(text)) return text ?? "";
        var s = Jwt.Replace(text, Redacted);
        s = Bearer.Replace(s, "Bearer " + Redacted);
        s = LaunchArg.Replace(s, "$1" + Redacted);
        s = KeyedSecret.Replace(s, "$1$2" + Redacted);
        s = SettingUser.Replace(s, "$1<player>");
        s = Email.Replace(s, "<email>");
        s = UserPath.Replace(s, "$1<user>");
        s = UnixUserPath.Replace(s, "$1<user>");
        if (knownNames != null)
            foreach (var name in knownNames.Where(n => n is { Length: >= 3 }).Distinct(StringComparer.OrdinalIgnoreCase))
                s = Regex.Replace(s, $@"(?<![A-Za-z0-9_]){Regex.Escape(name)}(?![A-Za-z0-9_])", "<user>",
                    RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        return s;
    }

    public sealed record Input(
        string LauncherVersion,
        string MinecraftVersion,
        int RamMb,
        int TotalRamMb,
        int ExitCode,
        CrashDiagnosis Diagnosis,
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
        sb.AppendLine($"Looks like   : {input.Diagnosis.Headline} ({input.Diagnosis.Cause})");
        if (input.Diagnosis.ModId != null) sb.AppendLine($"Mod named    : {input.Diagnosis.ModId}");

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
