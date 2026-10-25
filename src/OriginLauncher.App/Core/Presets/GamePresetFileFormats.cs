using System.Globalization;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace OriginLauncher.App.Core.Presets;

// How the preset engine reads and edits the three files without disturbing anything it does not own.

/// <summary>One file the engine edits key by key.</summary>
internal interface IKeyDoc
{
    string? Get(string key);
    bool Set(string key, string value);
    bool Remove(string key);
    string ToText();
}

/// <summary>
/// options.txt / optionsof.txt: one `key:value` per line, split at the FIRST ':'. Every line keeps its own line
/// ending, so an edit touches only the lines it changes; unknown keys, comments and odd lines survive as they are.
/// A key that appears twice is read and edited at its LAST line (the game loads line by line, later wins).
/// </summary>
internal sealed class KeyValueText : IKeyDoc
{
    private readonly List<(string Content, string Eol)> _lines;
    private readonly string _newline;

    private KeyValueText(List<(string Content, string Eol)> lines, string newline)
    {
        _lines = lines;
        _newline = newline;
    }

    /// <summary>Null for a damaged file: a crash mid-write can leave a file full of NUL bytes.</summary>
    public static KeyValueText? Parse(string text)
    {
        if (text.Contains('\0')) return null;
        var lines = new List<(string, string)>();
        string? newline = null;
        var start = 0;
        while (start < text.Length)
        {
            var end = text.IndexOf('\n', start);
            if (end < 0)
            {
                lines.Add((text[start..], ""));
                break;
            }
            var eol = end > start && text[end - 1] == '\r' ? "\r\n" : "\n";
            lines.Add((text[start..(end + 1 - eol.Length)], eol));
            newline ??= eol;
            start = end + 1;
        }
        return new KeyValueText(lines, newline ?? "\n");
    }

    public static KeyValueText Empty() => new(new List<(string, string)>(), "\n");

    public string ToText() => string.Concat(_lines.Select(l => l.Content + l.Eol));

    /// <summary>True when no key line is left (blank lines do not count): a file we created and emptied again.</summary>
    public bool HasNoKeys => _lines.All(l => string.IsNullOrWhiteSpace(l.Content));

    public string? Get(string key)
    {
        var index = LastIndexOf(key);
        return index < 0 ? null : _lines[index].Content[(key.Length + 1)..];
    }

    /// <summary>Sets the key's last line, or appends one. True when the text changed.</summary>
    public bool Set(string key, string value)
    {
        var line = key + ":" + value;
        var index = LastIndexOf(key);
        if (index >= 0)
        {
            if (_lines[index].Content == line) return false;
            _lines[index] = (line, _lines[index].Eol);
            return true;
        }
        // A last line without a line ending gets one first, so the new line starts on its own line.
        if (_lines.Count > 0 && _lines[^1].Eol.Length == 0) _lines[^1] = (_lines[^1].Content, _newline);
        _lines.Add((line, _newline));
        return true;
    }

    /// <summary>Removes every line of the key. True when there was one.</summary>
    public bool Remove(string key) => _lines.RemoveAll(l => IsKeyLine(l.Content, key)) > 0;

    private int LastIndexOf(string key)
    {
        for (var i = _lines.Count - 1; i >= 0; i--)
            if (IsKeyLine(_lines[i].Content, key)) return i;
        return -1;
    }

    private static bool IsKeyLine(string content, string key) =>
        content.Length > key.Length && content[key.Length] == ':' && content.StartsWith(key, StringComparison.Ordinal);
}

/// <summary>
/// config/originclient-mods.json: {"mods": {"&lt;modId&gt;": {"&lt;option&gt;": value}}, "hud": ..., "meta": ..., "profiles": ...}.
/// Only mods.&lt;modId&gt;.&lt;option&gt; is touched; the rest of the tree is carried through. The mod writes this file
/// with Gson (2-space indent), which this keeps.
/// </summary>
internal sealed class OriginConfigJson : IKeyDoc
{
    private static readonly JsonSerializerOptions Write = new()
    {
        WriteIndented = true,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private readonly JsonObject _root;

    private OriginConfigJson(JsonObject root) => _root = root;

    /// <summary>Null when the text is not a JSON object the mod could have written.</summary>
    public static OriginConfigJson? Parse(string text)
    {
        try
        {
            return JsonNode.Parse(text) is JsonObject root ? new OriginConfigJson(root) : null;
        }
        catch (Exception ex) when (ex is JsonException or ArgumentException or InvalidOperationException)
        {
            return null;
        }
    }

    public string ToText() => _root.ToJsonString(Write);

    /// <summary>The option's value as text ("true", "75", a string without quotes), or null when it is not set.</summary>
    public string? Get(string key)
    {
        var (mod, option) = Split(key);
        return _root["mods"] is JsonObject mods && mods[mod] is JsonObject o && o[option] is { } node ? Show(node) : null;
    }

    public bool Set(string key, string value)
    {
        var (mod, option) = Split(key);
        if (_root["mods"] is not JsonObject mods) _root["mods"] = mods = new JsonObject();
        if (mods[mod] is not JsonObject o) mods[mod] = o = new JsonObject();
        if (o[option] is { } existing && PresetValues.Same(key, Show(existing), value)) return false;
        o[option] = Make(value);
        return true;
    }

    /// <summary>Removes the option. True when it was there. An emptied mod object stays (the game rewrites it anyway).</summary>
    public bool Remove(string key)
    {
        var (mod, option) = Split(key);
        return _root["mods"] is JsonObject mods && mods[mod] is JsonObject o && o.Remove(option);
    }

    private static (string Mod, string Option) Split(string key)
    {
        var dot = key.IndexOf('.');
        return (key[..dot], key[(dot + 1)..]);
    }

    private static string Show(JsonNode node) =>
        node is JsonValue v && v.TryGetValue<string>(out var s) ? s : node.ToJsonString();

    // true / false / a number stay typed; anything else is a string.
    private static JsonNode Make(string value)
    {
        if (value == "true") return JsonValue.Create(true);
        if (value == "false") return JsonValue.Create(false);
        if (long.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var whole)) return JsonValue.Create(whole);
        if (double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var number)) return JsonValue.Create(number);
        return JsonValue.Create(value);
    }
}

/// <summary>When two stored values mean the same (Minecraft rewrites files in its own number and case style).</summary>
internal static class PresetValues
{
    public static bool Same(string key, string? a, string? b)
    {
        if (a == null || b == null) return a == b;
        a = Normalise(key, a);
        b = Normalise(key, b);
        if (string.Equals(a, b, StringComparison.Ordinal)) return true;
        if (string.Equals(a, b, StringComparison.OrdinalIgnoreCase) && (IsBool(a))) return true;
        return double.TryParse(a, NumberStyles.Float, CultureInfo.InvariantCulture, out var x)
            && double.TryParse(b, NumberStyles.Float, CultureInfo.InvariantCulture, out var y)
            && Math.Abs(x - y) <= 1e-6; // 0.5 vs 0.50, 75 vs 75.0
    }

    private static bool IsBool(string v) => v.Equals("true", StringComparison.OrdinalIgnoreCase) || v.Equals("false", StringComparison.OrdinalIgnoreCase);

    // 1.8.9 reads ao as 0/1/2 and also as true (2) / false (0); Settings Sync can leave the boolean form behind.
    private static string Normalise(string key, string value)
    {
        value = value.Trim();
        if (key == "ao")
            return value.ToLowerInvariant() switch { "true" => "2", "false" => "0", var other => other };
        return value;
    }
}
