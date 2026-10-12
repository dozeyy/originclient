namespace OriginLauncher.App.Core.Promos;

/// <summary>One launcher tip: its text and the bundled preview art behind it
/// (Assets/Promos/&lt;Preview&gt;.jpg, made by tools/launcher-previews).</summary>
public sealed record PromoTip(string Id, string Title, string Subtitle, string Preview);

/// <summary>
/// The tips the feed's "tips" slot cycles through, one per visit, plus the
/// bundled art for the feed's own house cards. Tips live in the launcher, not
/// the feed, because each needs preview art that ships with it; the feed only
/// decides whether (and where) the tips card appears.
/// </summary>
public static class PromoTips
{
    public static readonly IReadOnlyList<PromoTip> All = new[]
    {
        new PromoTip("menu", "Right Shift opens the Origin menu",
            "Turn mods on or off and tune every one of them", "menu"),
        new PromoTip("hud", "Drag your HUD anywhere",
            "In the Origin menu, Edit HUD moves FPS, keystrokes and more", "hud"),
        new PromoTip("map", "Press M for the full map",
            "The Minimap charts your singleplayer worlds as you explore", "map"),
        new PromoTip("screenshot", "Press F2, then copy it from chat",
            "Copy Image puts the screenshot on your clipboard for Discord", "chat"),
        new PromoTip("shaders", "Shaders come built in",
            "Iris + Sodium on every version. Pick a pack in Video Settings", "shaders"),
    };

    /// <summary>The tip for the slot's <paramref name="turn"/>-th visit (wraps; any int is fine).</summary>
    public static PromoTip At(int turn) => All[((turn % All.Count) + All.Count) % All.Count];

    /// <summary>
    /// Bundled preview art for a feed card that brings none of its own, by the
    /// card's id. Lets the live feed's long-standing cards get art without a
    /// feed change; "menu-tip" is the single tip card feeds had before the
    /// tips slot.
    /// </summary>
    public static string? BundledPreviewFor(string promoId) => promoId switch
    {
        "website" => "website",
        "menu-tip" => "menu",
        _ => null,
    };
}
