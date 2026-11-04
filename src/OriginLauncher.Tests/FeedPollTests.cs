using OriginLauncher.App.Core.Updates;
using Xunit;

namespace OriginLauncher.Tests;

// The update check's polling of GitHub's release feed (Core/Updates/FeedPoll): conditional requests so the unauthenticated
// limit (60 an hour per IP, shared by a whole network) is not spent, and GitHub's own waits honoured on a refusal.
public sealed class FeedPollTests
{
    private static readonly DateTime T0 = new(2026, 11, 4, 12, 0, 0, DateTimeKind.Utc);
    private const string Json = """{"tag_name":"launcher-v1.0.91","assets":[]}""";

    private static long Unix(DateTime utc) => new DateTimeOffset(utc).ToUnixTimeSeconds();

    [Fact]
    public void The_first_answer_is_kept_with_its_etag_and_sent_back_as_If_None_Match()
    {
        var feed = new FeedPoll();
        Assert.True(feed.MayAsk(T0));
        Assert.Null(feed.IfNoneMatch); // nothing for a 304 to stand for yet

        Assert.Equal(Json, feed.Answered(200, "W/\"abc\"", Json, null, "59", null, T0));
        Assert.Equal("W/\"abc\"", feed.IfNoneMatch);
        Assert.True(feed.MayAsk(T0));
    }

    [Fact]
    public void An_unchanged_feed_re_evaluates_the_last_answer()
    {
        var feed = new FeedPoll();
        feed.Answered(200, "\"e1\"", Json, null, null, null, T0);
        Assert.Equal(Json, feed.Answered(304, null, null, null, null, null, T0.AddMinutes(2)));
        Assert.Equal("\"e1\"", feed.IfNoneMatch);
    }

    [Fact]
    public void A_refusal_waits_for_Retry_After_and_keeps_evaluating_the_last_answer()
    {
        var feed = new FeedPoll();
        feed.Answered(200, "\"e1\"", Json, null, null, null, T0);
        Assert.Equal(Json, feed.Answered(429, null, null, TimeSpan.FromMinutes(7), null, null, T0));
        Assert.False(feed.MayAsk(T0.AddMinutes(6)));
        Assert.True(feed.MayAsk(T0.AddMinutes(7)));
    }

    [Fact]
    public void A_spent_limit_waits_for_X_RateLimit_Reset()
    {
        var feed = new FeedPoll();
        var reset = T0.AddMinutes(37);
        Assert.Null(feed.Answered(403, null, null, null, "0", Unix(reset).ToString(), T0)); // no body yet: nothing to evaluate
        Assert.False(feed.MayAsk(T0.AddMinutes(36)));
        Assert.True(feed.MayAsk(reset));

        // A 200 that spent the hour's last call also waits for the reset instead of being refused next time.
        var other = new FeedPoll();
        other.Answered(200, "\"e\"", Json, null, "0", Unix(reset).ToString(), T0);
        Assert.False(other.MayAsk(T0.AddMinutes(10)));
    }

    [Fact]
    public void Refusals_without_a_time_back_off_5_minutes_doubling_to_an_hour()
    {
        Assert.Equal(TimeSpan.FromMinutes(5), FeedPoll.BackOff(1));
        Assert.Equal(TimeSpan.FromMinutes(10), FeedPoll.BackOff(2));
        Assert.Equal(TimeSpan.FromMinutes(40), FeedPoll.BackOff(4));
        Assert.Equal(TimeSpan.FromMinutes(60), FeedPoll.BackOff(9));

        var feed = new FeedPoll();
        feed.Answered(403, null, null, null, null, null, T0);
        Assert.False(feed.MayAsk(T0.AddMinutes(4)));
        Assert.True(feed.MayAsk(T0.AddMinutes(5)));
        feed.Answered(403, null, null, null, null, null, T0.AddMinutes(5));
        Assert.False(feed.MayAsk(T0.AddMinutes(14)));
        // An answer resets the back-off.
        feed.Answered(200, null, Json, null, null, null, T0.AddMinutes(15));
        Assert.True(feed.MayAsk(T0.AddMinutes(15)));
    }

    [Fact]
    public void A_silly_header_never_stops_the_check_for_more_than_two_hours()
    {
        var feed = new FeedPoll();
        feed.Answered(429, null, null, TimeSpan.FromDays(3), null, null, T0);
        Assert.True(feed.MayAsk(T0 + FeedPoll.MaxWait));
    }

    [Fact]
    public void Any_other_answer_fails_open_with_nothing_to_evaluate()
    {
        var feed = new FeedPoll();
        feed.Answered(200, "\"e\"", Json, null, null, null, T0);
        Assert.Null(feed.Answered(502, null, null, null, null, null, T0));
        Assert.Null(feed.Answered(404, null, null, null, null, null, T0));
        Assert.True(feed.MayAsk(T0));
    }
}
