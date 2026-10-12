using System.Globalization;
using System.IO;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace OriginLauncher.App.Core.Sync;

// A JSON config, flattened to JSON Pointer keys ("/mods/zoom/fov",
// "/hud/coords") whose values are the leaf JSON text. Arrays are leaves (a HUD
// entry [anchor,dx,dy,scale,bg] is one value); objects never are. A file whose
// root isn't an object (originclient-waypoints.json is a list) is one value
// under the key "".
//
// Parsed as a loose JsonNode tree, like OriginClientConfigBridge: every field
// we don't touch is written back exactly as it was.
//
// Two modes:
//  * Origin's own configs (originclient*.json): every key is pushed, missing
//    objects are created, and a file the instance doesn't have yet is created
//    so a brand-new version starts with the player's settings. Checked in every
//    live module's loader: ModsConfig keeps every mod id / option / HUD id /
//    meta key / profile it reads (unknown ones included) and writes them all
//    back; ItemSizes is a plain id -> size map; Waypoints is one list;
//    OriginConfig (originclient.json) is Gson into a class, which ignores
//    unknown fields. And every option key has the same kind (toggle, slider,
//    dropdown, ...) in all 16 modules, so a value from one version is always
//    the right type in another.
//  * Third-party configs (sodium-options.json): the target's schema rules.
//    Only keys the target file ALREADY has, only when the new value is the
//    same JSON kind, and strings only within Sodium's quality enum
//    (DEFAULT/FANCY/FAST) — Sodium reads its file with Gson, where a type
//    mismatch throws at startup and an unknown enum name becomes null (a
//    crash later). The Sodium in the lineup ranges from 0.2 to 0.8, so its
//    schema genuinely differs between versions. A missing target file is
//    skipped (Sodium writes its own defaults on first run).
public sealed class JsonConfigFormat : ISyncFormat
{
    private static readonly JsonSerializerOptions WriteOptions = new()
    {
        WriteIndented = true,
        // Keep text readable for Gson and for anyone opening the file:
        // Gson reads either form, but the default encoder escapes '<', '&', é...
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private static readonly HashSet<string> SafeThirdPartyStrings = new(StringComparer.Ordinal)
    {
        "DEFAULT", "FANCY", "FAST",
    };

    private readonly bool _ownConfig;

    private JsonConfigFormat(bool ownConfig, bool allowsDeletions)
    {
        _ownConfig = ownConfig;
        AllowsDeletions = allowsDeletions;
    }

    /// <summary>
    /// An Origin config. <paramref name="allowsDeletions"/>: the file's loader
    /// never drops a key on its own, so a vanished key is the player resetting
    /// or deleting it (a HUD element reset, an item size reset, a deleted
    /// profile) and that should follow them too.
    /// </summary>
    public static JsonConfigFormat OriginConfig(bool allowsDeletions) => new(ownConfig: true, allowsDeletions);

    public static readonly JsonConfigFormat ThirdParty = new(ownConfig: false, allowsDeletions: false);

    public bool AllowsDeletions { get; }

    public Dictionary<string, string>? Read(string path)
    {
        var root = ParseFile(path, out var ok);
        return ok ? Flatten(root) : null;
    }

    public bool Rejects(string key, string raw) => false;

    public bool Equivalent(string key, string a, string b)
    {
        if (string.Equals(a, b, StringComparison.Ordinal)) return true;
        return TryParse(a, out var x) && TryParse(b, out var y) && JsonEquivalent(x, y);
    }

    public string BaselineOf(string raw) => raw;

    public bool MatchesBaseline(string key, string raw, string baseline) => Equivalent(key, raw, baseline);

    public Dictionary<string, string>? Apply(string path, IReadOnlyDictionary<string, string?> desired, SyncInstance target)
    {
        JsonNode? root = null;
        if (File.Exists(path))
        {
            root = ParseFile(path, out var ok);
            if (!ok) return null; // corrupt: leave it for the mod's own fallback
        }
        else if (!_ownConfig)
        {
            return null; // third-party: never create
        }

        var changed = false;
        foreach (var (key, raw) in desired)
        {
            if (raw == null)
            {
                changed |= _ownConfig && Remove(root, key);
                continue;
            }
            if (!TryParse(raw, out var value)) continue;

            if (key.Length == 0)
            {
                // Whole-file value (a list root such as the waypoints file).
                // Never replaces an object root — that file holds keyed settings.
                if (!_ownConfig || root is JsonObject || value is JsonObject) continue;
                if (root != null && JsonEquivalent(root, value)) continue;
                root = value;
                changed = true;
                continue;
            }

            if (root == null)
            {
                root = new JsonObject();
            }
            if (root is not JsonObject rootObject) continue;

            var segments = Segments(key);
            var parent = rootObject;
            var reachable = true;
            for (var i = 0; i < segments.Count - 1; i++)
            {
                var next = parent[segments[i]];
                if (next is JsonObject nextObject)
                {
                    parent = nextObject;
                }
                else if (next == null && !parent.ContainsKey(segments[i]) && _ownConfig)
                {
                    var created = new JsonObject();
                    parent[segments[i]] = created;
                    parent = created;
                }
                else
                {
                    reachable = false; // a non-object is in the way: schemas disagree
                    break;
                }
            }
            if (!reachable) continue;

            var leaf = segments[^1];
            var exists = parent.TryGetPropertyValue(leaf, out var current);
            if (exists && JsonEquivalent(current, value)) continue;
            if (!_ownConfig && !(exists && SameThirdPartyKind(current, value))) continue;

            parent[leaf] = value;
            changed = true;
        }

        if (!changed) return root == null ? null : Flatten(root);

        AtomicFile.WriteAllText(path, (root?.ToJsonString(WriteOptions) ?? "null") + Environment.NewLine);
        return Flatten(root);
    }

    // ---- third-party schema guard ----

    private static bool SameThirdPartyKind(JsonNode? current, JsonNode? value)
    {
        if (current is not JsonValue c || value is not JsonValue v) return false; // primitives only
        var ck = c.GetValueKind();
        var vk = v.GetValueKind();
        if (ck is JsonValueKind.True or JsonValueKind.False)
            return vk is JsonValueKind.True or JsonValueKind.False;
        if (ck == JsonValueKind.Number)
            return vk == JsonValueKind.Number;
        if (ck == JsonValueKind.String && vk == JsonValueKind.String)
            return SafeThirdPartyStrings.Contains(c.GetValue<string>()) && SafeThirdPartyStrings.Contains(v.GetValue<string>());
        return false;
    }

    // ---- deletions (Origin configs only) ----

    private static bool Remove(JsonNode? root, string key)
    {
        if (key.Length == 0 || root is not JsonObject parent) return false;
        var segments = Segments(key);
        for (var i = 0; i < segments.Count - 1; i++)
        {
            if (parent[segments[i]] is not JsonObject next) return false;
            parent = next;
        }
        return parent.Remove(segments[^1]);
    }

    // ---- flattening ----

    private static Dictionary<string, string> Flatten(JsonNode? root)
    {
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        if (root is JsonObject obj)
            FlattenInto(obj, "", map);
        else
            map[""] = root?.ToJsonString() ?? "null";
        return map;
    }

    // Objects are never values: an empty object contributes no key (writing
    // "{}" over a path would wipe what another version keeps under it).
    private static void FlattenInto(JsonObject obj, string prefix, Dictionary<string, string> map)
    {
        foreach (var (name, child) in obj)
        {
            var key = prefix + "/" + Escape(name);
            if (child is JsonObject childObject)
                FlattenInto(childObject, key, map);
            else
                map[key] = child?.ToJsonString() ?? "null";
        }
    }

    // RFC 6901 JSON Pointer escaping: '~' -> "~0", '/' -> "~1".
    private static string Escape(string name) => name.Replace("~", "~0").Replace("/", "~1");

    private static List<string> Segments(string key) =>
        key.Split('/').Skip(1).Select(s => s.Replace("~1", "/").Replace("~0", "~")).ToList();

    // ---- parsing / comparison ----

    private static JsonNode? ParseFile(string path, out bool ok)
    {
        ok = false;
        try
        {
            var text = File.ReadAllText(path);
            if (text.Contains('\0') || string.IsNullOrWhiteSpace(text)) return null;
            var root = JsonNode.Parse(text, documentOptions: new JsonDocumentOptions
            {
                AllowTrailingCommas = true,
                CommentHandling = JsonCommentHandling.Skip,
            });
            ok = true;
            return root;
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static bool TryParse(string raw, out JsonNode? node)
    {
        try
        {
            node = JsonNode.Parse(raw);
            return true;
        }
        catch (JsonException)
        {
            node = null;
            return false;
        }
    }

    private static bool JsonEquivalent(JsonNode? a, JsonNode? b)
    {
        if (a == null || b == null) return a == null && b == null;
        switch (a)
        {
            case JsonObject ao when b is JsonObject bo:
                return ao.Count == bo.Count
                    && ao.All(p => bo.TryGetPropertyValue(p.Key, out var other) && JsonEquivalent(p.Value, other));
            case JsonArray aa when b is JsonArray ba:
                return aa.Count == ba.Count && aa.Zip(ba).All(p => JsonEquivalent(p.First, p.Second));
            case JsonValue av when b is JsonValue bv:
                var ak = av.GetValueKind();
                var bk = bv.GetValueKind();
                if (ak == JsonValueKind.Number && bk == JsonValueKind.Number)
                {
                    // Gson writes 1.0 where another writer has 1: same number.
                    var x = double.Parse(av.ToJsonString(), NumberStyles.Float, CultureInfo.InvariantCulture);
                    var y = double.Parse(bv.ToJsonString(), NumberStyles.Float, CultureInfo.InvariantCulture);
                    return Math.Abs(x - y) <= 1e-9 * Math.Max(1, Math.Abs(x));
                }
                return ak == bk && av.ToJsonString() == bv.ToJsonString();
            default:
                return false;
        }
    }
}
