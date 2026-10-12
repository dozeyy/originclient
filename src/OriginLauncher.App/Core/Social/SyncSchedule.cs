namespace OriginLauncher.App.Core.Social;

/// <summary>What the next /v1/sync delay depends on.</summary>
public readonly record struct SyncInputs(
    DateTimeOffset Now,
    bool DrawerOpen,
    bool InParty,
    DateTimeOffset? LastLiveEvent,
    DateTimeOffset? InactiveSince);

/// <summary>
/// The request budget from docs/SOCIAL.md, as one pure function. Cloudflare's
/// free plan is 100,000 requests a day, so the launcher only polls fast while
/// something live is happening:
///   4 s  — the Friends drawer (list or a chat) is open, or a DM / invite /
///          request arrived less than 2 minutes ago;
///   20 s — in a party otherwise (the launcher never shares positions, so
///          it never gets wantState);
///   30 s — otherwise;
///   60 s — the launcher window has been in the background for over 5 minutes
///          (wins over everything above: the game syncs for itself).
/// Failures back off 5 s, 10 s, 20 s ... up to 5 minutes; a failed sign-in
/// backs off from 1 minute up to 30 (Microsoft / Mojang must not be hammered).
/// </summary>
public static class SyncSchedule
{
    public static readonly TimeSpan Live = TimeSpan.FromSeconds(4);
    public static readonly TimeSpan Party = TimeSpan.FromSeconds(20);
    public static readonly TimeSpan Idle = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan Background = TimeSpan.FromSeconds(60);
    public static readonly TimeSpan LiveWindow = TimeSpan.FromMinutes(2);
    public static readonly TimeSpan InactiveAfter = TimeSpan.FromMinutes(5);

    /// <summary>No Microsoft account: nothing to sync. Account changes wake the loop sooner.</summary>
    public static readonly TimeSpan SignedOutRecheck = TimeSpan.FromMinutes(10);

    private static readonly TimeSpan BackoffStart = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan BackoffMax = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan AuthBackoffStart = TimeSpan.FromMinutes(1);
    private static readonly TimeSpan AuthBackoffMax = TimeSpan.FromMinutes(30);

    public static TimeSpan NextDelay(SyncInputs i)
    {
        if (i.InactiveSince is { } since && i.Now - since > InactiveAfter)
            return Background;
        if (i.DrawerOpen)
            return Live;
        if (i.LastLiveEvent is { } live && i.Now - live < LiveWindow)
            return Live;
        return i.InParty ? Party : Idle;
    }

    /// <summary>Delay after the <paramref name="failures"/>-th failure in a row (network, 5xx, 429).</summary>
    public static TimeSpan Backoff(int failures) => Doubling(BackoffStart, BackoffMax, failures);

    /// <summary>Delay after the <paramref name="failures"/>-th failed sign-in in a row.</summary>
    public static TimeSpan AuthBackoff(int failures) => Doubling(AuthBackoffStart, AuthBackoffMax, failures);

    private static TimeSpan Doubling(TimeSpan start, TimeSpan max, int failures)
    {
        var steps = Math.Clamp(failures - 1, 0, 30);
        var ticks = start.Ticks * Math.Pow(2, steps);
        return ticks >= max.Ticks ? max : TimeSpan.FromTicks((long)ticks);
    }
}
