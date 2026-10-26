namespace OriginLauncher.App.UI;

/// <summary>
/// The top-bar tabs' switch model (MainWindow.NavigateTo). A click shows its page at once; the page's own refresh
/// (settings and account reads, row rebuilds) runs after that frame, and only for the NEWEST click: each click takes a
/// ticket, and a refresh whose ticket is no longer current is dropped. Spamming Home / Mods / Profiles / Settings
/// therefore shows each page as it is clicked and refreshes only the one the player stops on, instead of queueing a
/// disk read and a rebuild per click (Will, 2026-10-25: "switching between tabs feels slow and lags if you spam them").
/// A refresh that changes what the page must show on its first frame (the Mods page pointed at another version) is
/// the caller's to run at once. Pure (no WPF), so OriginLauncher.Tests covers it.
/// </summary>
internal sealed class PageSwitch<TPage> where TPage : struct, Enum
{
    private int _ticket;

    public PageSwitch(TPage start) => Current = start;

    /// <summary>The page on screen (the last one switched to).</summary>
    public TPage Current { get; private set; }

    /// <summary>
    /// A click on <paramref name="page"/>. Null when it is already the page on screen (nothing to show or refresh);
    /// otherwise the ticket its deferred refresh must still hold when it runs (<see cref="IsNewest"/>).
    /// </summary>
    public int? Go(TPage page)
    {
        if (EqualityComparer<TPage>.Default.Equals(page, Current)) return null;
        Current = page;
        return ++_ticket;
    }

    /// <summary>True while no later click has happened since <paramref name="ticket"/> was taken.</summary>
    public bool IsNewest(int ticket) => ticket == _ticket;
}
