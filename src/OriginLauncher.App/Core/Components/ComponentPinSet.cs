using System.Text.Json;
using System.Text.Json.Serialization;

namespace OriginLauncher.App.Core.Components;

/// <summary>
/// A profile's component pins (<c>ModProfile.ComponentPins</c>): one string, "family=pairId" entries separated by ';',
/// for example "sodium-iris=0.6.5+1.8.1". A pin narrows the configurations Play may pick to the one with that pair
/// (Fix Compatibility writes it when an older pair is what makes the profile start). Written only through
/// <c>SettingsStore.Update</c>; reading it never throws: anything that is not a well-formed entry is dropped.
/// </summary>
public static class ComponentPinSet
{
    public const int MaxEntries = 8;

    /// <summary>The entries that are well formed; an empty map for null, empty or garbage text.</summary>
    public static IReadOnlyDictionary<string, string> Parse(string? text)
    {
        var pins = new Dictionary<string, string>(StringComparer.Ordinal);
        if (string.IsNullOrWhiteSpace(text) || text.Length > 1024) return pins;
        foreach (var raw in text.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var eq = raw.IndexOf('=');
            if (eq <= 0) continue;
            var family = raw[..eq];
            var id = raw[(eq + 1)..];
            if (!IsFamily(family) || !IsPinId(id) || pins.ContainsKey(family)) continue;
            pins[family] = id;
            if (pins.Count == MaxEntries) break;
        }
        return pins;
    }

    /// <summary>The string form of <paramref name="pins"/> (sorted, so the same pins always read the same); null when empty.</summary>
    public static string? Format(IReadOnlyDictionary<string, string> pins)
    {
        var parts = pins.Where(p => IsFamily(p.Key) && IsPinId(p.Value))
            .OrderBy(p => p.Key, StringComparer.Ordinal)
            .Take(MaxEntries)
            .Select(p => p.Key + "=" + p.Value)
            .ToList();
        return parts.Count == 0 ? null : string.Join(";", parts);
    }

    /// <summary><paramref name="text"/> with <paramref name="family"/> set to <paramref name="pinId"/> (null removes it).</summary>
    public static string? With(string? text, string family, string? pinId)
    {
        if (!IsFamily(family)) throw new ArgumentException("Not a component family.", nameof(family));
        if (pinId != null && !IsPinId(pinId)) throw new ArgumentException("Not a pin id.", nameof(pinId));
        var pins = new Dictionary<string, string>(Parse(text), StringComparer.Ordinal);
        if (pinId == null) pins.Remove(family);
        else pins[family] = pinId;
        return Format(pins);
    }

    /// <summary>a-z, 0-9 and '-', 1 to 32 characters ("sodium-iris").</summary>
    public static bool IsFamily(string? s) =>
        s is { Length: >= 1 and <= 32 } && s.All(c => c is (>= 'a' and <= 'z') or (>= '0' and <= '9') or '-');

    /// <summary>Letters, digits and . + _ -, 1 to 64 characters ("0.6.5+1.8.1"). Safe inside a folder name.</summary>
    public static bool IsPinId(string? s) =>
        s is { Length: >= 1 and <= 64 } && s[0] != '.' && s[^1] != '.' &&
        s.All(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '+' or '_' or '-');
}

/// <summary>
/// Reads a string property without ever failing the whole settings file: a string is kept as written, any other
/// JSON value (a number, an object a hand edit left) reads as null. settings.json is one document, and one bad value
/// must not reset every other setting (SettingsStore falls back to defaults on a JsonException).
/// </summary>
public sealed class LenientStringJsonConverter : JsonConverter<string?>
{
    public override bool HandleNull => true;

    public override string? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        if (reader.TokenType == JsonTokenType.String) return reader.GetString();
        reader.Skip();
        return null;
    }

    public override void Write(Utf8JsonWriter writer, string? value, JsonSerializerOptions options)
    {
        if (value == null) writer.WriteNullValue();
        else writer.WriteStringValue(value);
    }
}
