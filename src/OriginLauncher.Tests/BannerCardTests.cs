using OriginLauncher.App.Core.Promos;
using Xunit;

namespace OriginLauncher.Tests;

public class BannerCardTests
{
    private static HousePromo Link(string id, string? url) =>
        new(id, PromoKind.Link, "ORIGIN", "Title", "", null, url == null ? null : new Uri(url));

    private static readonly HousePromo Tips = new("tips", PromoKind.Tips, "TIP", "", "", null, null);
    private static readonly HousePromo Discord = new("discord", PromoKind.Discord, "DISCORD", "", "", null, null);

    [Fact]
    public void LinkedCardsOpenTheirLinkAndTipsAdvance()
    {
        Assert.Equal(CardClick.OpenLink, BannerCard.ClickFor(Link("website", "https://originclient.org/"), 3));
        Assert.Equal(CardClick.OpenLink, BannerCard.ClickFor(Discord, 3));
        Assert.Equal(CardClick.Advance, BannerCard.ClickFor(Tips, 3));
        // A lone tips card still moves on: the next visit is the next tip.
        Assert.Equal(CardClick.Advance, BannerCard.ClickFor(Tips, 1));
    }

    [Fact]
    public void ACardWithoutALinkAdvancesUnlessItIsAlone()
    {
        Assert.Equal(CardClick.Advance, BannerCard.ClickFor(Link("news", null), 2));
        Assert.Equal(CardClick.None, BannerCard.ClickFor(Link("news", null), 1));
    }

    [Fact]
    public void TheLiveFeedsCardsClickTheWayTheyShould()
    {
        var feed = PromoFeedParser.TryParse(File.ReadAllText(Path.Combine(TestRepo.Root, "website", "launcher", "promos.json")))!;
        var slides = PromoRotation.Playable(feed, discordReady: true);
        foreach (var promo in slides)
        {
            var expected = promo.Kind == PromoKind.Tips || (promo.Kind == PromoKind.Link && promo.ClickUrl == null)
                ? CardClick.Advance : CardClick.OpenLink;
            Assert.Equal(expected, BannerCard.ClickFor(promo, slides.Count));
        }
    }

    [Fact]
    public void TextStepsShrinkToASensibleMinimumThenWiden()
    {
        var steps = BannerCard.TextSteps;
        Assert.Equal(15, steps[0].TitleSize);
        Assert.Equal(12, steps[0].SubtitleSize);
        for (var i = 1; i < steps.Count; i++)
        {
            Assert.True(steps[i].TitleSize <= steps[i - 1].TitleSize);
            Assert.True(steps[i].SubtitleSize <= steps[i - 1].SubtitleSize);
        }
        Assert.All(steps, s => Assert.True(s.TitleSize >= 12 && s.SubtitleSize >= 10.5));
        Assert.All(steps, s => Assert.True(s.TitleLineHeight >= s.TitleSize && s.SubtitleLineHeight >= s.SubtitleSize));
        Assert.True(steps[^1].Wide);
        Assert.All(steps.Take(steps.Count - 1), s => Assert.False(s.Wide));
    }

    [Fact]
    public void ChoosesTheLargestStepThatFitsElseTheLast()
    {
        Assert.Same(BannerCard.TextSteps[0], BannerCard.ChooseTextStep(_ => true));
        Assert.Same(BannerCard.TextSteps[2], BannerCard.ChooseTextStep(s => s.TitleSize <= 13));
        Assert.Same(BannerCard.TextSteps[^1], BannerCard.ChooseTextStep(_ => false));
    }

    // ---- Every shipped string fits the real card ---------------------------
    // A model of PromoBanner's text box, in the launcher's font. Monocraft is
    // monospaced (every glyph advances 2/3 em), so wrapping is predictable;
    // the SemiBold title is Windows' simulated bold, ~1 px wider per glyph
    // (measured on the CI render), modelled as size/14 to stay on the safe side.
    // The numbers mirror PromoBanner.xaml: card 96 px tall, 1 px border,
    // SlideContent margin 6 top / 11 bottom → 77 px of height; text column
    // capped at 250 px (TextMaxWidth); the chip is ~15 px (17 here for margin) with 6 px below it,
    // and 2 px sit between title and subtitle. The CI launcher render
    // (RenderShots, banner-*.png) is the picture that confirms it.
    private const double BoxHeight = 77, ColumnWidth = 250, Chip = 17, Gaps = 6 + 2;

    private static int Lines(string text, double fontSize, bool bold = false)
    {
        var perLine = (int)Math.Floor(ColumnWidth / (fontSize * 2 / 3 + (bold ? fontSize / 14 : 0)));
        var lines = 1;
        var used = 0;
        foreach (var word in text.Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
            var length = word.Length;
            if (used > 0 && used + 1 + length <= perLine) { used += 1 + length; continue; }
            if (used > 0) lines++;
            // A word longer than the line breaks inside itself.
            lines += (length - 1) / perLine;
            used = (length - 1) % perLine + 1;
        }
        return lines;
    }

    private static bool Fits(BannerTextStep step, string title, string subtitle) =>
        Chip + Gaps + Lines(title, step.TitleSize, bold: true) * step.TitleLineHeight
                    + (subtitle.Length == 0 ? 0 : Lines(subtitle, step.SubtitleSize) * step.SubtitleLineHeight)
        <= BoxHeight;

    // Fits at a real size step — the wide last resort is for surprises, not for shipped text.
    private static bool FitsAtSomeSize(string title, string subtitle) =>
        BannerCard.TextSteps.Where(s => !s.Wide).Any(s => Fits(s, title, subtitle));

    private static void AssertFits(string what, string title, string subtitle) =>
        Assert.True(FitsAtSomeSize(title, subtitle),
            $"{what} doesn't fit the launcher banner even at the smallest text size — shorten it: \"{title}\" / \"{subtitle}\"");

    [Fact]
    public void EveryTipFitsTheBannerWhole()
    {
        foreach (var tip in PromoTips.All) AssertFits($"tip '{tip.Id}'", tip.Title, tip.Subtitle);
    }

    [Fact]
    public void EveryFeedCardFitsTheBannerWhole()
    {
        var live = PromoFeedParser.TryParse(File.ReadAllText(Path.Combine(TestRepo.Root, "website", "launcher", "promos.json")))!;
        foreach (var feed in new[] { live, PromoFeedLoader.BuiltIn().Feed })
            foreach (var promo in feed.Promos.Where(p => p.Kind == PromoKind.Link))
                AssertFits($"feed card '{promo.Id}'", promo.Title, promo.Subtitle);
    }

    [Fact]
    public void TheFitModelCatchesTextThatIsTooLong()
    {
        // The two tips that used to end in "…" on the card.
        Assert.False(FitsAtSomeSize(
            "Press F2, then copy it from chat", "Copy Image puts the screenshot on your clipboard for Discord"));
        Assert.False(FitsAtSomeSize(
            "Right Shift opens the Origin menu", "Turn mods on or off and tune every one of them"));
        Assert.True(Fits(BannerCard.TextSteps[0], "Drag your HUD anywhere", "Short"));
    }
}

internal static class TestRepo
{
    public static string Root { get; } = Find();

    private static string Find()
    {
        var dir = AppContext.BaseDirectory;
        while (dir != null && !File.Exists(Path.Combine(dir, "OriginLauncher.sln")))
            dir = Path.GetDirectoryName(dir);
        return dir ?? throw new InvalidOperationException("repo root not found");
    }
}
