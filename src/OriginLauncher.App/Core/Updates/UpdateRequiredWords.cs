namespace OriginLauncher.App.Core.Updates;

/// <summary>
/// What Play says when a mandatory update refuses it, the same on Windows, Mac and Linux (DECISIONS 2026-11-02: the Mac
/// launcher has every screen and feature of the Windows one). The status line alone was easy to miss (Will, 2026-11-03:
/// "it just won't let you launch"), so every launcher also opens this prompt, whose Update button runs the chip's install.
/// </summary>
public static class UpdateRequiredWords
{
    public const string Status = "Update required - press Update at the top";
    public const string PromptTitle = "Update required";
    // Updates are mandatory (DECISIONS 2026-07-08), so "Not now" can't open Play: the text says so up front, and the
    // Update button continues straight into the game the player pressed Play for (PlayAfterUpdate, bug run 5, JL-5).
    public const string PromptText =
        "A new version of Origin is out, and Play needs it. It only takes a moment: Origin reopens by itself and starts your game.";
    public const string UpdateButton = "Update and play";
    public const string LaterButton = "Not now";
}
