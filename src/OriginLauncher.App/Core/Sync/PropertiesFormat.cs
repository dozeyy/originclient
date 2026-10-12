using System.IO;
using System.Text;

namespace OriginLauncher.App.Core.Sync;

// A Java .properties file written by a third-party mod — Iris's
// config/iris.properties (shaderPack, enableShaders, colorSpace,
// maxShadowRenderDistance, ...). Lines are edited in place: comments, the
// timestamp header, order and escaping are kept, and only `key=value` lines
// the TARGET already has are touched (never add foreign keys — the Iris in the
// lineup spans many releases, each with its own key set).
//
// shaderPack names a pack inside the instance's own shaderpacks/ folder, and
// pack files are not synced. So it is only pushed when that pack is really
// there (or the value is empty = shaders off). Iris itself treats a missing
// pack as "shaders off" without saving, so this is about not silently turning
// the player's shaders off in a version that doesn't have their pack.
public sealed class PropertiesFormat : ISyncFormat
{
    public static readonly PropertiesFormat Iris = new();

    private const string ShaderPackKey = "shaderPack";

    public bool AllowsDeletions => false;

    public Dictionary<string, string>? Read(string path)
    {
        var lines = ReadLines(path);
        return lines == null ? null : ToMap(lines);
    }

    public bool Rejects(string key, string raw) => raw.IndexOfAny(new[] { '\r', '\n', '\0' }) >= 0;

    public bool Equivalent(string key, string a, string b) => string.Equals(a.Trim(), b.Trim(), StringComparison.Ordinal);

    public string BaselineOf(string raw) => raw;

    public bool MatchesBaseline(string key, string raw, string baseline) => Equivalent(key, raw, baseline);

    public Dictionary<string, string>? Apply(string path, IReadOnlyDictionary<string, string?> desired, SyncInstance target)
    {
        if (!File.Exists(path)) return null; // the mod writes its own file first
        var lines = ReadLines(path);
        if (lines == null) return null;

        var changed = false;
        foreach (var (key, raw) in desired)
        {
            if (raw == null || Rejects(key, raw)) continue;
            var index = IndexOfKey(lines, key);
            if (index < 0) continue; // never add keys the target's version doesn't have
            if (Equivalent(key, ValueOf(lines[index]), raw)) continue;
            if (key == ShaderPackKey && !ShaderPackPresent(target.Directory, raw)) continue;
            lines[index] = key + "=" + raw.Trim();
            changed = true;
        }

        if (changed)
            AtomicFile.WriteAllText(path, string.Join(Environment.NewLine, lines) + Environment.NewLine);
        return ToMap(lines);
    }

    private static bool ShaderPackPresent(string instanceDirectory, string raw)
    {
        var name = Unescape(raw.Trim());
        if (name.Length == 0) return true;
        if (name.IndexOfAny(Path.GetInvalidPathChars()) >= 0 || name.Contains("..")) return false;
        var packPath = Path.Combine(instanceDirectory, "shaderpacks", name);
        return File.Exists(packPath) || Directory.Exists(packPath);
    }

    // Java Properties escapes (Properties.store): "\ " "\:" "\=" "\\" "\t" "\uXXXX"...
    private static string Unescape(string value)
    {
        var sb = new StringBuilder(value.Length);
        for (var i = 0; i < value.Length; i++)
        {
            var c = value[i];
            if (c != '\\' || i + 1 >= value.Length)
            {
                sb.Append(c);
                continue;
            }
            var next = value[++i];
            switch (next)
            {
                case 't': sb.Append('\t'); break;
                case 'n': sb.Append('\n'); break;
                case 'r': sb.Append('\r'); break;
                case 'f': sb.Append('\f'); break;
                case 'u' when i + 4 < value.Length
                              && int.TryParse(value.AsSpan(i + 1, 4), System.Globalization.NumberStyles.HexNumber, null, out var code):
                    sb.Append((char)code);
                    i += 4;
                    break;
                default: sb.Append(next); break;
            }
        }
        return sb.ToString();
    }

    private static List<string>? ReadLines(string path)
    {
        try
        {
            var text = File.ReadAllText(path, Encoding.Latin1); // Properties.store writes ISO-8859-1 + \u escapes
            if (text.Contains('\0')) return null;
            var lines = text.Replace("\r\n", "\n").Split('\n').ToList();
            if (lines.Count > 0 && lines[^1].Length == 0) lines.RemoveAt(lines.Count - 1);
            return lines;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    // Plain `key=value` lines only; comments (# !), continuations and keys
    // containing escapes are left alone — Iris writes none of those for settings.
    private static Dictionary<string, string> ToMap(List<string> lines)
    {
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var line in lines)
        {
            var key = KeyOf(line);
            if (key != null) map[key] = ValueOf(line);
        }
        return map;
    }

    private static string? KeyOf(string line)
    {
        var trimmed = line.TrimStart();
        if (trimmed.Length == 0 || trimmed[0] is '#' or '!' || line.EndsWith('\\')) return null;
        var equals = line.IndexOf('=');
        if (equals <= 0) return null;
        var key = line[..equals].Trim();
        return key.Length == 0 || key.Contains('\\') || key.Contains(':') || key.Contains(' ') ? null : key;
    }

    private static int IndexOfKey(List<string> lines, string key)
    {
        for (var i = lines.Count - 1; i >= 0; i--)
            if (KeyOf(lines[i]) == key) return i;
        return -1;
    }

    private static string ValueOf(string line) => line[(line.IndexOf('=') + 1)..].TrimStart();
}
