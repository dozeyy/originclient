using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace OriginLauncher.App.Core.Renderers;

/// <summary>What <see cref="IrisDependencyOverride.Ensure"/> found: whether the override is in the file now, and why not.</summary>
public readonly record struct IrisOverrideResult(bool InPlace, bool Changed, string? Problem);

/// <summary>
/// The one loader setting a Vulkan game needs in the account's own config folder (1.21.1 only; Core/Renderers/VulkanMode).
///
/// Why: Origin's VulkaIris fork no longer claims the mod id "sodium" (Flywheel/Create would load Sodium classes if
/// anything did), and a Vulkan launch disables Origin's nested Sodium. Iris still declares "depends sodium", so without
/// help Fabric Loader refuses to start. Fabric Loader's own supported fix is config/fabric_loader_dependencies.json:
/// <c>{"version":1,"overrides":{"iris":{"-depends":{"sodium":"*"}}}}</c> drops that one dependency. CI writes the same
/// file for its Vulkan rows (tools/vulkan-probe/fabric_loader_dependencies.json, boot-smoke/dev-shots renderer=vulkan).
///
/// The file can also hold the player's own overrides, so Origin only ever touches overrides.iris."-depends".sodium:
/// Ensure adds it next to whatever is there, Remove (every 1.21.1 launch on normal graphics) takes it out again and
/// deletes the file only when nothing else is left. A file Origin can't read (bad JSON, another format version) is
/// never rewritten: Ensure reports it so the launch stays on normal graphics (the existing fallback), Remove leaves it.
/// Writes are atomic (temp file + rename) and only happen when something changes. Never throws.
/// </summary>
public static class IrisDependencyOverride
{
    /// <summary>Fabric Loader's file name; it reads it from &lt;game dir&gt;/config (InstanceLayout.LoaderDependenciesFile).</summary>
    public const string FileName = "fabric_loader_dependencies.json";

    private const string ModId = "iris";
    private const string RemoveKey = "-depends";
    private const string DependencyId = "sodium";

    private static readonly JsonSerializerOptions Indented = new() { WriteIndented = true };

    /// <summary>Makes sure <paramref name="file"/> drops Iris's sodium dependency, keeping every other override in it.</summary>
    public static IrisOverrideResult Ensure(string file)
    {
        try
        {
            JsonObject root;
            if (File.Exists(file))
            {
                if (!TryRead(file, out var existing, out var problem)) return new(false, false, problem);
                root = existing!;
            }
            else
            {
                root = new JsonObject { ["version"] = 1 };
            }

            if (root["overrides"] is null) root["overrides"] = new JsonObject();
            var overrides = (JsonObject)root["overrides"]!; // shape checked by TryRead
            if (overrides[ModId] is null) overrides[ModId] = new JsonObject();
            var iris = (JsonObject)overrides[ModId]!;
            if (iris[RemoveKey] is null) iris[RemoveKey] = new JsonObject();
            var removed = (JsonObject)iris[RemoveKey]!;
            if (removed.ContainsKey(DependencyId)) return new(true, false, null); // already there: no write

            removed[DependencyId] = "*";
            Write(file, root);
            return new(true, true, null);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return new(false, false, $"couldn't write {FileName} ({ex.Message})");
        }
    }

    /// <summary>
    /// Takes Origin's entry back out of <paramref name="file"/> (a launch on normal graphics). Null when the file is fine
    /// (removed, never there, or no file), else why it was left alone.
    /// </summary>
    public static string? Remove(string file)
    {
        try
        {
            if (!File.Exists(file)) return null;
            if (!TryRead(file, out var read, out var problem)) return problem;
            var root = read!;
            if (root["overrides"] is not JsonObject overrides
                || overrides[ModId] is not JsonObject iris
                || iris[RemoveKey] is not JsonObject removed
                || !removed.Remove(DependencyId))
                return null; // Origin's entry isn't there: nothing to do, no write

            if (removed.Count == 0) iris.Remove(RemoveKey);
            if (iris.Count == 0) overrides.Remove(ModId);
            // Only "version" and an empty "overrides" left: the file was Origin's alone.
            if (overrides.Count == 0 && root.All(p => p.Key is "version" or "overrides"))
            {
                File.Delete(file);
                return null;
            }
            Write(file, root);
            return null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return $"couldn't update {FileName} ({ex.Message})";
        }
    }

    // The file as Fabric Loader reads it: an object with "version": 1 and, when present, objects for "overrides",
    // overrides.iris and iris."-depends". Anything else is the player's (or broken) and is never rewritten.
    private static bool TryRead(string file, out JsonObject? root, out string? problem)
    {
        root = null;
        problem = null;
        try { return Check(JsonNode.Parse(File.ReadAllText(file)), out root, out problem); }
        // Bad JSON, or a duplicate key (JsonObject throws ArgumentException when it materialises one).
        catch (Exception ex) when (ex is JsonException or ArgumentException or InvalidOperationException)
        {
            root = null;
            problem = $"{FileName} isn't valid JSON ({ex.Message}); left as it is";
            return false;
        }
    }

    private static bool Check(JsonNode? node, out JsonObject? root, out string? problem)
    {
        root = null;
        problem = null;
        if (node is not JsonObject obj)
        {
            problem = $"{FileName} isn't a JSON object; left as it is";
            return false;
        }
        if (obj["version"] is not JsonValue v || !v.TryGetValue<int>(out var version) || version != 1)
        {
            problem = $"{FileName} isn't format version 1; left as it is";
            return false;
        }
        var overrides = obj["overrides"];
        var iris = (overrides as JsonObject)?[ModId];
        var removed = (iris as JsonObject)?[RemoveKey];
        if ((overrides != null && overrides is not JsonObject)
            || (iris != null && iris is not JsonObject)
            || (removed != null && removed is not JsonObject))
        {
            problem = $"{FileName} has an unexpected shape; left as it is";
            return false;
        }
        root = obj;
        return true;
    }

    private static void Write(string file, JsonObject root)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        var temp = file + ".origin-tmp";
        File.WriteAllText(temp, root.ToJsonString(Indented));
        File.Move(temp, file, overwrite: true);
    }
}
