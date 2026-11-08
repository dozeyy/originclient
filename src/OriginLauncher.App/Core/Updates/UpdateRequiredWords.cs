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

/// <summary>
/// What a Linux launcher says when a newer release exists but this copy can't replace itself where it sits
/// (UpdateService.Linux FindNotUpdatableReason gives the reason code; UpdateService.Blocked carries the sentence). The
/// Linux twin of the Mac's "Move to Applications": updates are mandatory, so the words always say how to get them.
/// </summary>
public static class LinuxUpdateWords
{
    public const string ReadOnlyAppImage = "the AppImage's folder is read-only";
    public const string ReadOnlyFolder = "the install folder is read-only";
    public const string FlatpakInstall = "Flatpak updates through Flatpak";

    /// <summary>The sentence for a reason code, or null when the copy can update itself.</summary>
    public static string? Blocked(string? reason, bool appImage) => reason switch
    {
        null => null,
        ReadOnlyAppImage => "Origin can't update itself from this folder. Move the AppImage to a folder you own, like ~/Applications, and open it from there.",
        ReadOnlyFolder => "Origin can't update itself in this folder. Move the OriginLauncher folder somewhere you own, like your home folder, and open it from there.",
        FlatpakInstall => "This copy of Origin updates through Flatpak. Update it there to get the newest version.",
        _ => appImage
            ? "Origin can't update itself here. Download the newest AppImage from originclient.org."
            : "Origin can't update itself here. Download the newest version from originclient.org.",
    };
}
