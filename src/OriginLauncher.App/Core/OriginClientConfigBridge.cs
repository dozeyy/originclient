using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using OriginLauncher.App.Core.GameFolders.Launcher;

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

    // Every account's game folder of every Origin-supported version has its own
    // originclient.json. The toggle is a single global preference, so it is
    // written to ALL of them and read as "on unless some folder was explicitly
    // turned off". A folder created later is seeded from an existing one's
    // configs (Core/GameFolders/AccountSeed), so it inherits the setting.
    private static IEnumerable<string> ConfigPaths =>
        AccountFolders.AllGameDirs().Select(dir => Path.Combine(dir, "config", FileName));

    // Defaults to true (matching OriginFeatures.java's own default) when no
    // instance has been launched yet, so the toggle reflects reality instead of
    // guessing. If any existing instance config says false, the toggle shows off.
    public static bool IsOriginUiEnabled()
    {
        foreach (var path in ConfigPaths)
        {
            if (!File.Exists(path))
                continue;

            try
            {
                var node = JsonNode.Parse(File.ReadAllText(path));
                if (node?[FieldName]?.GetValue<bool>() == false)
                    return false;
            }
            catch (Exception ex) when (ex is JsonException or InvalidOperationException or IOException or UnauthorizedAccessException)
            {
                // treat an unreadable config as default-on
            }
        }

        return true;
    }

    public static void SetOriginUiEnabled(bool enabled)
    {
        foreach (var path in ConfigPaths)
        {
            // One locked/unwritable folder (its game is running) must not stop the rest.
            try { WriteFlag(path, enabled); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        }
    }

    /// <summary>
    /// A game folder was just created: carry the global switch into it when it
    /// is OFF (a seeded folder already has it; one with nothing to seed from
    /// would otherwise come up with the Origin UI on).
    /// </summary>
    public static void ApplyToNewFolder(string gameDir)
    {
        try
        {
            if (!IsOriginUiEnabled())
                WriteFlag(Path.Combine(gameDir, "config", FileName), false);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // best-effort; the player can flip the switch again
        }
    }

    private static void WriteFlag(string configPath, bool enabled)
    {
        JsonObject root;
        if (File.Exists(configPath))
        {
            try
            {
                root = JsonNode.Parse(File.ReadAllText(configPath)) as JsonObject ?? new JsonObject();
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

        Directory.CreateDirectory(Path.GetDirectoryName(configPath)!);
        File.WriteAllText(configPath, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
    }
}
