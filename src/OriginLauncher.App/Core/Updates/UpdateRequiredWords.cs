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
    public const string PromptText =
        "A new version of Origin is out. Update to keep playing - it only takes a moment, and the launcher reopens by itself.";
    public const string UpdateButton = "Update";
    public const string LaterButton = "Not now";
}
