using System.IO;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.GameFolders;

// A game folder Minecraft has never saved options into starts on vanilla's first-run prompts instead of
// Origin's title: the accessibility "Welcome" onboarding page (1.19.4+, before the title), the
// "Multiplayer Warning" on the first Multiplayer click, the WASD / jump / inventory tutorial toasts in the
// first world and the Social Interactions toast on the first server. Vanilla shows each one because its
// options.txt key still has the first-run default. Origin's CI already skips them (boot-smoke and dev-shots
// seed onboardAccessibility:false), so this is the same thing for players.
//
// Runs on every launch, after Settings Sync and the "Recommended for this PC" preset (PresetWriter creates
// a new folder's options.txt; seeding first would make it skip). Only ADDS a key the file lacks: once
// Minecraft has saved options.txt every key is present, so a player's own value (or a synced / imported
// one) is never overwritten. The cost on a returning folder is one small file read.
//
// Not seeded: Simple Voice Chat's onboarding_finished. Finishing SVC's onboarding is what switches voice
// chat on (ClientPlayerStateManager.canEnable is false while onboarding), so seeding it would turn voice
// chat on for players who never chose it. It stays the player's own step until Will decides otherwise.
// 1.8.9 has none of these prompts (its options have no tutorial, warning or onboarding keys).
public static class FirstRunOptions
{
    /// <summary>
    /// The first-run keys for <paramref name="version"/> and the value a returning player has. Era-gated to the
    /// versions whose Options class reads the key (an unknown key is ignored by every loader, but writing only real
    /// keys keeps the file honest).
    /// </summary>
    public static Dictionary<string, string> For(string version)
    {
        var keys = new Dictionary<string, string>(StringComparer.Ordinal);
        if (!McVersion.AtLeast(version, "1.16")) return keys; // 1.8.9: none of these exist
        keys["tutorialStep"] = "none";            // since 1.12 (the WASD / jump / inventory toasts)
        keys["skipMultiplayerWarning"] = "true";  // since 1.15.2 (the "Multiplayer Warning" screen)
        keys["joinedFirstServer"] = "true";       // since 1.16.4 (the "Press P for Social Interactions" toast)
        if (McVersion.AtLeast(version, "1.19.4"))
            keys["onboardAccessibility"] = "false"; // the accessibility "Welcome" page shown before the title
        return keys;
    }

    /// <summary>
    /// Adds the first-run keys <paramref name="gameDir"/>'s options.txt lacks. Returns the number of keys written
    /// (0: nothing missing, nothing for this version, or the file could not be written safely). Never throws.
    /// </summary>
    public static int Seed(string gameDir, string version)
    {
        try
        {
            if (!Directory.Exists(gameDir)) return 0;
            var path = Path.Combine(gameDir, "options.txt");
            var existing = new Dictionary<string, string>(StringComparer.Ordinal);
            if (File.Exists(path))
            {
                // Unreadable or corrupt (NUL bytes after a crash mid-write): leave it to vanilla.
                if (OptionsTxtFormat.Instance.Read(path) is not { } read) return 0;
                existing = read;
            }

            var missing = new Dictionary<string, string?>(StringComparer.Ordinal);
            foreach (var (key, value) in For(version))
                if (!existing.ContainsKey(key)) missing[key] = value;
            if (missing.Count == 0) return 0;

            // Apply keeps every other line as it is and stamps vanilla's data version on a file that has none
            // (or writes nothing when the version's data version is unknown: null).
            return OptionsTxtFormat.Instance.Apply(path, missing, new SyncInstance(version, gameDir)) == null ? 0 : missing.Count;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return 0;
        }
    }
}
