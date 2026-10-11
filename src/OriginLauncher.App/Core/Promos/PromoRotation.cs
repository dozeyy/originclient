namespace OriginLauncher.App.Core.Promos;

/// <summary>Which house promos can show right now, and in what order.</summary>
public static class PromoRotation
{
    /// <summary>
    /// The feed's promos in feed order, minus the Discord slot when there is
    /// no live server info to fill it (no invite yet, invalid invite, or
    /// Discord not reached yet) — the card is left out rather than shown half-empty.
    /// </summary>
    public static IReadOnlyList<HousePromo> Playable(PromoFeed feed, bool discordReady) =>
        feed.Promos.Where(p => p.Kind != PromoKind.Discord || (discordReady && feed.Discord.HasInvite)).ToList();

    /// <summary>The slide after <paramref name="current"/>, wrapping; -1 when there are none.</summary>
    public static int Next(int current, int count) => count <= 0 ? -1 : (Math.Max(current, -1) + 1) % count;

    /// <summary>
    /// After the slide list changes (feed refresh, Discord card appearing),
    /// stay on the same promo if it's still there so nothing visibly jumps;
    /// otherwise start from the first.
    /// </summary>
    public static int IndexAfterChange(IReadOnlyList<HousePromo> slides, string? currentId)
    {
        if (slides.Count == 0) return -1;
        if (currentId == null) return 0;
        for (var i = 0; i < slides.Count; i++)
            if (slides[i].Id == currentId) return i;
        return 0;
    }
}
