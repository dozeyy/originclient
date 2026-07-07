using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace OriginLauncher.App.Core;

// Reads/writes just the "originUiEnabled" flag in the Origin Client mod's own
// originclient.json — the same file OriginConfig.java (Gson, no naming
// policy) owns as its primary reader/writer. Parses as a loose JsonNode tree
// rather than a fixed-shape object: the launcher only ever touches this one
// field, so any other settings the player changed in-game (zoom FOV, HUD
// toggle, etc.) are read back and rewritten completely untouched instead of
// risking being dropped or reordered by a round-trip through a narrower C#
// model that doesn't know about them.
public static class OriginClientConfigBridge
{
    private const string FileName = "originclient.json";
    private const string FieldName = "originUiEnabled";

    // Origin Client only targets this exact MC version today (must stay in
    // sync with VersionManager.OriginClientModVersion and
    // OriginClient.Mod/gradle.properties' minecraft_version).
    private const string InstanceVersion = "1.21.1";

    private static string ConfigPath => Path.Combine(OriginPaths.Instances, InstanceVersion, "config", FileName);

    // Defaults to true (matching OriginFeatures.java's own default) when the
    // instance has never been launched yet, so the toggle reflects reality
    // instead of guessing.
    public static bool IsOriginUiEnabled()
    {
        if (!File.Exists(ConfigPath))
            return true;

        try
        {
            var node = JsonNode.Parse(File.ReadAllText(ConfigPath));
            return node?[FieldName]?.GetValue<bool>() ?? true;
        }
        catch (JsonException)
        {
            return true;
        }
    }

    public static void SetOriginUiEnabled(bool enabled)
    {
        JsonObject root;
        if (File.Exists(ConfigPath))
        {
            try
            {
                root = JsonNode.Parse(File.ReadAllText(ConfigPath)) as JsonObject ?? new JsonObject();
            }
            catch (JsonException)
            {
                root = new JsonObject();
            }
        }
        else
        {
            root = new JsonObject();
        }

        root[FieldName] = enabled;

        Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
        File.WriteAllText(ConfigPath, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
    }
}
