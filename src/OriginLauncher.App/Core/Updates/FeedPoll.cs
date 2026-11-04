using System.Globalization;

namespace OriginLauncher.App.Core.Updates;

/// <summary>
/// How the update check asks GitHub's release feed without burning its unauthenticated limit (60 calls an hour per IP,
/// shared by every PC on the same network: two Origin PCs in a house, a school LAN, lots of alt-tabbing). Pure: the HTTP
/// answer and the clock in, the release JSON to evaluate out. UpdateService owns one and asks it under its lock.
///  - Conditional requests: the last answer's ETag goes back as If-None-Match, and GitHub's 304 ("unchanged") does not
///    count against the limit. A 304 re-evaluates the last body, so everything that runs per poll still runs.
///  - Refused (403 / 429): no request until GitHub's own time (Retry-After, or X-RateLimit-Reset when the limit is spent),
///    else 5 min doubling to 60 min. Meanwhile each poll re-evaluates the last body without asking.
///  - Anything else (5xx, a missing feed, a network error): fails open exactly as before; nothing is evaluated.
/// </summary>
public sealed class FeedPoll
{
    public static readonly TimeSpan FirstBackOff = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan MaxBackOff = TimeSpan.FromMinutes(60);
    /// <summary>The longest any header may hold the check off (a wrong clock or a silly header never stops it for good).</summary>
    public static readonly TimeSpan MaxWait = TimeSpan.FromHours(2);

    /// <summary>The ETag of <see cref="Body"/>.</summary>
    public string? ETag { get; private set; }

    /// <summary>The last release JSON the feed sent (null until the first 200).</summary>
    public string? Body { get; private set; }

    /// <summary>No request before this (UTC); MinValue = ask any time.</summary>
    public DateTime NotBeforeUtc { get; private set; } = DateTime.MinValue;

    private int _refusals;

    /// <summary>Whether to ask the feed now. False while backing off: evaluate <see cref="Body"/> instead.</summary>
    public bool MayAsk(DateTime nowUtc) => nowUtc >= NotBeforeUtc;

    /// <summary>The If-None-Match to send: only when there is a body a 304 can stand for.</summary>
    public string? IfNoneMatch => Body != null ? ETag : null;

    /// <summary>
    /// The feed answered <paramref name="status"/>. Returns the release JSON to evaluate: the new body (200), the last
    /// one (304, or a refusal: nothing new is known), or null (any other answer: fail open, as before).
    /// </summary>
    /// <param name="rateRemaining">The X-RateLimit-Remaining header, as sent (null when absent).</param>
    /// <param name="rateReset">The X-RateLimit-Reset header (Unix seconds), as sent (null when absent).</param>
    public string? Answered(int status, string? etag, string? body, TimeSpan? retryAfter,
        string? rateRemaining, string? rateReset, DateTime nowUtc)
    {
        var spent = rateRemaining?.Trim() == "0";
        var resetAt = ParseReset(rateReset);
        switch (status)
        {
            case 200 when body != null:
                ETag = etag;
                Body = body;
                _refusals = 0;
                // That was the hour's last call: the next one waits for the reset instead of being refused.
                NotBeforeUtc = spent && resetAt is { } at ? Clamp(at, nowUtc) : DateTime.MinValue;
                return body;
            case 304:
                _refusals = 0;
                return Body;
            case 403:
            case 429:
                _refusals++;
                var wait = retryAfter
                           ?? (spent && resetAt is { } reset ? reset - nowUtc : (TimeSpan?)null)
                           ?? BackOff(_refusals);
                NotBeforeUtc = Clamp(nowUtc + wait, nowUtc);
                return Body;
            default:
                return null;
        }
    }

    /// <summary>The wait after the n-th refusal in a row with no time from GitHub: 5, 10, 20, 40, 60, 60... minutes.</summary>
    public static TimeSpan BackOff(int refusals) =>
        TimeSpan.FromMinutes(Math.Min(FirstBackOff.TotalMinutes * Math.Pow(2, Math.Max(0, refusals - 1)), MaxBackOff.TotalMinutes));

    private static DateTime Clamp(DateTime at, DateTime nowUtc) =>
        at < nowUtc ? nowUtc : at > nowUtc + MaxWait ? nowUtc + MaxWait : at;

    private static DateTime? ParseReset(string? header) =>
        long.TryParse(header?.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var seconds) && seconds > 0
            ? DateTimeOffset.FromUnixTimeSeconds(Math.Min(seconds, 253402300799)).UtcDateTime
            : null;
}
