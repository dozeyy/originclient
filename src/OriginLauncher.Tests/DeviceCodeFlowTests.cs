using OriginLauncher.App.Core.Auth;
using Xunit;

namespace OriginLauncher.Tests;

public class DeviceCodeFlowTests
{
    private const string Redirect = "https://login.live.com/oauth20_desktop.srf";

    [Fact]
    public void Start_reads_every_field()
    {
        var s = DeviceCodeFlow.ParseStart("""
            {"device_code":"DC-secret","user_code":" ABCD-EFGH ","verification_uri":"https://www.microsoft.com/link",
             "interval":7,"expires_in":"600"}
            """);
        Assert.NotNull(s);
        Assert.Equal("DC-secret", s!.DeviceCode);
        Assert.Equal("ABCD-EFGH", s.UserCode);
        Assert.Equal("https://www.microsoft.com/link", s.VerificationUri);
        Assert.Equal(7, s.IntervalSeconds);
        Assert.Equal(600, s.ExpiresInSeconds);
    }

    [Fact]
    public void Start_never_prints_either_code()
    {
        var text = new DeviceCodeStart("DC-secret", "ABCD-EFGH", "https://www.microsoft.com/link", 5, 900).ToString();
        Assert.DoesNotContain("DC-secret", text);
        Assert.DoesNotContain("ABCD-EFGH", text);
        Assert.Contains("https://www.microsoft.com/link", text);
    }

    [Fact]
    public void Start_fills_missing_interval_and_expiry_with_defaults()
    {
        var s = DeviceCodeFlow.ParseStart("""{"device_code":"d","user_code":"u","verification_uri":"https://x.test/"}""");
        Assert.Equal(DeviceCodeFlow.DefaultIntervalSeconds, s!.IntervalSeconds);
        Assert.Equal(DeviceCodeFlow.DefaultExpiresSeconds, s.ExpiresInSeconds);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("not json")]
    [InlineData("[]")]
    [InlineData("""{"user_code":"u","verification_uri":"https://x.test/"}""")]
    [InlineData("""{"device_code":"d","verification_uri":"https://x.test/"}""")]
    [InlineData("""{"device_code":"d","user_code":"u"}""")]
    [InlineData("""{"device_code":"d","user_code":"u","verification_uri":"http://plain.test/"}""")]
    [InlineData("""{"device_code":"d","user_code":"u","verification_uri":"javascript:alert(1)"}""")]
    public void Start_refuses_an_answer_the_player_cannot_use(string? json) =>
        Assert.Null(DeviceCodeFlow.ParseStart(json));

    [Theory]
    [InlineData(true, null, DeviceCodePoll.Done)]
    [InlineData(false, """{"error":"authorization_pending"}""", DeviceCodePoll.Pending)]
    [InlineData(false, """{"error":"slow_down"}""", DeviceCodePoll.SlowDown)]
    [InlineData(false, """{"error":"expired_token"}""", DeviceCodePoll.Expired)]
    [InlineData(false, """{"error":"authorization_declined"}""", DeviceCodePoll.Declined)]
    [InlineData(false, """{"error":"access_denied"}""", DeviceCodePoll.Declined)]
    [InlineData(false, """{"error":"bad_verification_code"}""", DeviceCodePoll.Failed)]
    [InlineData(false, "", DeviceCodePoll.Failed)]
    [InlineData(false, "<html>", DeviceCodePoll.Failed)]
    public void Each_poll_answer_is_read(bool ok, string? body, DeviceCodePoll expected) =>
        Assert.Equal(expected, DeviceCodeFlow.Classify(ok, body));

    [Fact]
    public void Slow_down_adds_five_seconds_and_pending_keeps_the_pace()
    {
        Assert.Equal(10, DeviceCodeFlow.NextInterval(5, DeviceCodePoll.SlowDown));
        Assert.Equal(5, DeviceCodeFlow.NextInterval(5, DeviceCodePoll.Pending));
    }

    [Fact]
    public void Redirect_with_our_state_gives_the_code()
    {
        var o = SignInRedirect.Classify($"{Redirect}?code=M.C1&state=abc&lc=1033", Redirect, "abc", out var code, out _);
        Assert.Equal(RedirectOutcome.Code, o);
        Assert.Equal("M.C1", code);
    }

    [Fact]
    public void The_sign_in_page_itself_is_not_the_redirect_even_though_it_carries_it()
    {
        var page = "https://login.live.com/oauth20_authorize.srf?redirect_uri=" + Uri.EscapeDataString(Redirect) + "&state=abc";
        Assert.Equal(RedirectOutcome.NotOurs, SignInRedirect.Classify(page, Redirect, "abc", out _, out _));
    }

    [Theory]
    [InlineData("?code=X&state=other", RedirectOutcome.Ignore)]
    [InlineData("?error=access_denied", RedirectOutcome.Ignore)]
    [InlineData("?code=X", RedirectOutcome.Error)]
    [InlineData("?error=invalid_scope&state=abc", RedirectOutcome.Error)]
    [InlineData("?code=&state=abc", RedirectOutcome.Error)]
    public void Redirect_state_rules(string query, RedirectOutcome expected) =>
        Assert.Equal(expected, SignInRedirect.Classify(Redirect + query, Redirect, "abc", out _, out _));

    [Fact]
    public void Redirect_error_names_the_reason()
    {
        SignInRedirect.Classify($"{Redirect}?error=invalid_scope&state=abc", Redirect, "abc", out _, out var error);
        Assert.Equal("invalid_scope", error);
    }

    // A network blip mid-poll (a 5xx page, a 408) is waited out like "pending"; a real OAuth answer (400 with an error
    // field, 401) is never mistaken for one.
    [Theory]
    [InlineData(500, true)]
    [InlineData(502, true)]
    [InlineData(503, true)]
    [InlineData(504, true)]
    [InlineData(408, true)]
    [InlineData(400, false)]
    [InlineData(401, false)]
    [InlineData(200, false)]
    public void Transient_statuses_are_waited_out(int status, bool transient) =>
        Assert.Equal(transient, DeviceCodeFlow.IsTransientStatus(status));

    [Fact]
    public void Blips_in_a_row_are_capped()
    {
        for (var miss = 1; miss <= DeviceCodeFlow.MaxTransientMisses; miss++)
            Assert.True(DeviceCodeFlow.KeepWaitingAfterMiss(miss));
        Assert.False(DeviceCodeFlow.KeepWaitingAfterMiss(DeviceCodeFlow.MaxTransientMisses + 1));
        Assert.Equal(5, DeviceCodeFlow.MaxTransientMisses);
    }
}
