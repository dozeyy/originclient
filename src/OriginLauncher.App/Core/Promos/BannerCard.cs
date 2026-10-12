namespace OriginLauncher.App.Core.Promos;

/// <summary>What clicking the home banner card does for the card on screen.</summary>
public enum CardClick
{
    /// <summary>Open the card's link in the browser (website, Discord invite).</summary>
    OpenLink,
    /// <summary>Move on to the next card, exactly like the auto-rotation does.</summary>
    Advance,
    /// <summary>Nothing to do (a lone card with no link).</summary>
    None,
}

/// <summary>
/// One size step for the banner card's text: the title and subtitle font sizes
/// with their line heights (fixed per step, so a wrapped line costs a known
/// amount of the card's height). <paramref name="Wide"/> lifts the text
/// column's width cap so the text may run further over the preview art — the
/// last resort before anything would be cut off.
/// </summary>
public sealed record BannerTextStep(
    double TitleSize, double TitleLineHeight,
    double SubtitleSize, double SubtitleLineHeight,
    bool Wide = false);

/// <summary>
/// The banner card's pure rules (UI/Controls/PromoBanner applies them):
/// what a click does, and the text-size ladder that keeps every card's text
/// fully inside the card — wrapped, then shrunk a step at a time, never cut
/// off with "…".
/// </summary>
public static class BannerCard
{
    /// <summary>
    /// Cards that link somewhere open their link; every other card (the tips,
    /// a feed card with no clickUrl) advances to the next card. The tips slot
    /// advances even when it's the only card — the next visit is the next tip.
    /// </summary>
    public static CardClick ClickFor(HousePromo promo, int slideCount) => promo.Kind switch
    {
        PromoKind.Discord => CardClick.OpenLink,
        PromoKind.Link when promo.ClickUrl != null => CardClick.OpenLink,
        PromoKind.Tips => CardClick.Advance,
        _ => slideCount > 1 ? CardClick.Advance : CardClick.None,
    };

    /// <summary>
    /// Largest first. The design size (15 / 12) is step 0; each step is a
    /// little smaller, down to 12 / 10.5 — smaller than that stops being
    /// comfortable to read in the launcher's pixel font. The final step keeps
    /// the minimum size but lets the text use the card's full width.
    /// Line heights are ~1.2x the size (Monocraft's ink fills one em).
    /// </summary>
    public static readonly IReadOnlyList<BannerTextStep> TextSteps = new[]
    {
        new BannerTextStep(15, 18, 12, 14),
        new BannerTextStep(14, 17, 11.5, 14),
        new BannerTextStep(13, 16, 11, 13),
        new BannerTextStep(12, 14, 10.5, 13),
        new BannerTextStep(12, 14, 10.5, 13, Wide: true),
    };

    /// <summary>
    /// The first (largest) step whose text <paramref name="fits"/> the card;
    /// the last step when none does, which is the most that can be shown.
    /// </summary>
    public static BannerTextStep ChooseTextStep(Func<BannerTextStep, bool> fits)
    {
        foreach (var step in TextSteps)
            if (fits(step)) return step;
        return TextSteps[^1];
    }
}
