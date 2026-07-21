using System.Collections.Generic;
using System.IO;

namespace OriginLauncher.App.Core.Loaders;

// Simple Voice Chat is bundled on every version that has a build, but Origin
// ships it INERT — exactly like JEI, it's installed and ready but does nothing
// until the player turns it on. SVC has its own native "disabled" state (its
// ClientPlayerStateManager/RenderEvents self-gate on these keys), so Origin
// only has to write that state on first install; it never needs a mixin.
//
// The keys, in SVC's own config/voicechat/voicechat-client.properties:
//   disabled            = true  -> mic + HUD + nametag icons all off (mod inert)
//   hide_icons          = true  -> no voice icons even if something re-enables
//   onboarding_finished = true  -> kills the first-join "set up voice chat" chat
//                                  nudge SVC shows new players (since 2.5.2)
//
// Unlike IrisConfigSeeder (which enforces its key on EVERY launch as a standing
// policy), this writes ONLY when the file does not yet exist. Once the player
// opens SVC's own settings (default key V) and turns voice chat on, that writes
// this same file — re-seeding disabled=true on a later launch would silently
// undo their choice, so we never touch an existing file.
public static class VoiceChatConfigSeeder
{
    // SVC keeps its client config in a "voicechat" subfolder of config/.
    private const string SubFolder = "voicechat";
    private const string FileName = "voicechat-client.properties";

    private static readonly IReadOnlyList<string> SeedLines = new[]
    {
        "disabled=true",
        "hide_icons=true",
        "onboarding_finished=true",
    };

    /// <summary>
    /// On a FIRST install (no SVC config yet), write the inert defaults so Voice
    /// Chat is silent until the player enables it from SVC's own screen. A no-op
    /// once the file exists, so the player's later choice always wins.
    /// </summary>
    public static void SeedIfAbsent(string configFolder)
    {
        var dir = Path.Combine(configFolder, SubFolder);
        var path = Path.Combine(dir, FileName);
        if (File.Exists(path))
            return; // player (or SVC itself) already owns this file — never overwrite

        Directory.CreateDirectory(dir);
        File.WriteAllLines(path, SeedLines);
    }
}
