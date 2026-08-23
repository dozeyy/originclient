using System.Text;

namespace OriginLauncher.App.Core.Launch;

// Turns the free-text JVM arguments box into individual arguments.
public static class JvmArgLine
{
    /// <summary>
    /// Splits on any whitespace (the box is multi-line, so newlines separate
    /// arguments exactly like spaces do), keeping double-quoted runs together
    /// so a path with spaces survives as one argument.
    /// </summary>
    public static IReadOnlyList<string> Split(string? text)
    {
        var tokens = new List<string>();
        if (string.IsNullOrWhiteSpace(text)) return tokens;

        var current = new StringBuilder();
        var inQuotes = false;

        foreach (var ch in text)
        {
            if (ch == '"')
            {
                inQuotes = !inQuotes;
                current.Append(ch);
            }
            else if (!inQuotes && char.IsWhiteSpace(ch))
            {
                Flush();
            }
            else
            {
                current.Append(ch);
            }
        }
        Flush();
        return tokens;

        void Flush()
        {
            if (current.Length == 0) return;
            var token = current.ToString();
            // Strip quotes that wrap the WHOLE token ("-XX:+UseZGC" -> -XX:+UseZGC)
            // but keep ones quoting a value inside it, since
            // -Dfoo="C:\Program Files\x" needs its quotes to reach the JVM intact.
            if (token.Length >= 2 && token[0] == '"' && token[^1] == '"')
                token = token[1..^1];
            tokens.Add(token);
            current.Clear();
        }
    }

    /// <summary>
    /// Non-blocking sanity check for the editor's status line. Returns null when
    /// nothing looks wrong. Never rejects input — an unknown flag may well be
    /// valid on a JVM this launcher knows nothing about.
    /// </summary>
    public static string? Warn(IReadOnlyList<string> tokens)
    {
        // The RAM slider already emits -Xmx/-Xms (and the G1 preset is tuned
        // around them being equal), so a heap flag typed here fights it.
        var heap = tokens.FirstOrDefault(t =>
            t.StartsWith("-Xmx", StringComparison.OrdinalIgnoreCase) ||
            t.StartsWith("-Xms", StringComparison.OrdinalIgnoreCase));
        if (heap != null)
            return $"\"{heap}\" conflicts with the RAM allocation slider — set memory there instead.";

        var notAFlag = tokens.FirstOrDefault(t => !t.StartsWith('-'));
        if (notAFlag != null)
            return $"\"{notAFlag}\" doesn't look like a JVM flag (they start with -).";

        return null;
    }
}
