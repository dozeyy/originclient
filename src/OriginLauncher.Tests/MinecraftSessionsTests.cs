using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Auth.Sessions;
using Xunit;

namespace OriginLauncher.Tests;

// When the launcher signs in to Minecraft (MinecraftSessions). Mojang
// rate-limits login_with_xbox (HTTP 429, the v1.0.49 "can't add an account"
// bug), so a session is reused per account until shortly before it expires,
// survives restarts (the vault), and a 429 holds sign-ins off. A fake clock,
// vault and Microsoft chain — nothing touches the network or DPAPI.
public class MinecraftSessionsTests
{
    private const string Steve = "069a79f444e94726a5befca90e38aaf5";
    private const string Alex = "853c80ef3c3749fdaa49938b674adae6";

    private static readonly DateTimeOffset Start = new(2026, 10, 12, 12, 0, 0, TimeSpan.Zero);

    private DateTimeOffset _now = Start;
    private readonly FakeVault _vault = new();
    private readonly FakeSignIn _signIn;
    private readonly MinecraftSessions _sessions;

    public MinecraftSessionsTests()
    {
        _signIn = new FakeSignIn(() => _now);
        _sessions = new MinecraftSessions(_vault, _signIn, () => _now);
    }

    private static MinecraftSession SessionFor(string uuid, DateTimeOffset expires, string token = "saved") =>
        new(uuid == Steve ? "Steve" : "Alex", uuid, token, "xuid", expires);

    // --- reuse ------------------------------------------------------------

    [Fact]
    public async Task First_use_signs_in_and_saves_the_session_and_rotated_token()
    {
        _vault.Add(Steve, "rt-0");

        var session = await _sessions.GetAsync(Steve);

        Assert.Equal(1, _signIn.Calls);
        Assert.Equal(Steve, session!.Uuid);
        Assert.Equal(Start.AddHours(24), session.ExpiresAt);
        Assert.Equal("rt-1", _vault.Accounts[Steve].Refresh);
        Assert.Same(session, _vault.Accounts[Steve].Session);
    }

    [Fact]
    public async Task Play_presses_reuse_the_session_for_hours()
    {
        _vault.Add(Steve, "rt-0");

        await _sessions.ForPlayAsync(Steve);
        _now = Start.AddHours(5);
        await _sessions.ForPlayAsync(Steve);
        _now = Start.AddHours(22);
        await _sessions.ForPlayAsync(Steve);

        Assert.Equal(1, _signIn.Calls);
        Assert.Equal(2, _signIn.Checks); // the reused ones are checked with Mojang (cheap, not rate-limited)
        Assert.Equal(3, _vault.UsedMarks);
    }

    [Fact]
    public async Task A_saved_session_survives_a_restart()
    {
        _vault.Add(Steve, "rt-0", SessionFor(Steve, Start.AddHours(20)));

        var session = await _sessions.ForPlayAsync(Steve);

        Assert.Equal(0, _signIn.Calls);
        Assert.Equal("saved", session!.AccessToken);
    }

    [Fact]
    public async Task Signs_in_again_inside_the_last_hour_before_expiry()
    {
        _vault.Add(Steve, "rt-0", SessionFor(Steve, Start.AddMinutes(59)));

        var session = await _sessions.GetAsync(Steve);

        Assert.Equal(1, _signIn.Calls);
        Assert.NotEqual("saved", session!.AccessToken);
    }

    [Fact]
    public async Task Each_account_keeps_its_own_session_across_switches()
    {
        _vault.Add(Steve, "rt-s");
        _vault.Add(Alex, "rt-a");

        for (var i = 0; i < 3; i++)
        {
            await _sessions.ForPlayAsync(Steve);
            await _sessions.GetAsync(Alex);
            _now += TimeSpan.FromMinutes(20);
        }

        Assert.Equal(2, _signIn.Calls); // one per account, not one per switch
    }

    [Fact]
    public async Task Concurrent_callers_share_one_sign_in()
    {
        _vault.Add(Steve, "rt-0");
        _signIn.Hold = new TaskCompletionSource();

        var play = _sessions.ForPlayAsync(Steve);
        var friends = _sessions.GetAsync(Steve);
        var friendsAgain = _sessions.RenewAsync(Steve);
        await _signIn.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        _signIn.Hold.SetResult();
        var sessions = await Task.WhenAll(play, friends, friendsAgain);

        Assert.Equal(1, _signIn.Calls);
        Assert.All(sessions, s => Assert.Equal(sessions[0]!.AccessToken, s!.AccessToken));
    }

    [Fact]
    public async Task No_readable_refresh_token_means_re_add_the_account()
    {
        Assert.Null(await _sessions.GetAsync(Steve));
        Assert.Equal(0, _signIn.Calls);
    }

    // --- Mojang refusing a token ----------------------------------------------

    [Fact]
    public async Task Play_signs_in_again_when_Mojang_refuses_the_cached_token()
    {
        _vault.Add(Steve, "rt-0", SessionFor(Steve, Start.AddHours(20)));
        _signIn.CheckAnswer = TokenCheck.Rejected;

        var session = await _sessions.ForPlayAsync(Steve);

        Assert.Equal(1, _signIn.Calls);
        Assert.NotEqual("saved", session!.AccessToken);
    }

    [Fact]
    public async Task Play_uses_the_cached_token_when_Mojang_cant_be_asked()
    {
        _vault.Add(Steve, "rt-0", SessionFor(Steve, Start.AddHours(20)));
        _signIn.CheckAnswer = TokenCheck.Unknown;

        var session = await _sessions.ForPlayAsync(Steve);

        Assert.Equal(0, _signIn.Calls);
        Assert.Equal("saved", session!.AccessToken);
    }

    [Fact]
    public async Task Friends_renew_at_most_once_per_30_minutes()
    {
        _vault.Add(Steve, "rt-0");

        await _sessions.GetAsync(Steve);                 // sign-in #1
        await _sessions.RenewAsync(Steve);               // too soon: same session
        _now = Start.AddMinutes(29);
        await _sessions.RenewAsync(Steve);               // still too soon
        Assert.Equal(1, _signIn.Calls);

        _now = Start.AddMinutes(31);
        await _sessions.RenewAsync(Steve);               // sign-in #2
        Assert.Equal(2, _signIn.Calls);
    }

    [Fact]
    public async Task Renewing_a_session_loaded_after_a_restart_signs_in()
    {
        _vault.Add(Steve, "rt-0", SessionFor(Steve, Start.AddHours(20)));

        var session = await _sessions.RenewAsync(Steve);

        Assert.Equal(1, _signIn.Calls);
        Assert.NotEqual("saved", session!.AccessToken);
    }

    // --- 429 -------------------------------------------------------------------

    [Fact]
    public async Task A_429_without_Retry_After_holds_sign_ins_off_for_10_minutes()
    {
        _vault.Add(Steve, "rt-0");
        _signIn.Fail = MicrosoftAuthException.Limited("minecraft_login", null);

        var first = await Assert.ThrowsAsync<MicrosoftAuthException>(() => _sessions.GetAsync(Steve));
        Assert.True(first.RateLimited);
        Assert.Equal(MicrosoftAuthException.RateLimitedMessage, first.Message);
        Assert.Equal("rt-0", _vault.Accounts[Steve].Refresh); // nothing rotated, nothing lost

        // Pressing Play again right away never reaches Microsoft.
        _signIn.Fail = null;
        _now = Start.AddMinutes(9);
        var again = await Assert.ThrowsAsync<MicrosoftAuthException>(() => _sessions.ForPlayAsync(Steve));
        Assert.True(again.RateLimited);
        Assert.Equal(1, _signIn.Calls);

        _now = Start.AddMinutes(10).AddSeconds(1);
        Assert.NotNull(await _sessions.ForPlayAsync(Steve));
        Assert.Equal(2, _signIn.Calls);
    }

    [Fact]
    public async Task Retry_After_is_honoured()
    {
        _vault.Add(Steve, "rt-0");
        _signIn.Fail = MicrosoftAuthException.Limited("xsts", TimeSpan.FromMinutes(3));
        await Assert.ThrowsAsync<MicrosoftAuthException>(() => _sessions.GetAsync(Steve));
        _signIn.Fail = null;

        Assert.Equal(Start.AddMinutes(3), _sessions.BlockedUntil(Steve));
        _now = Start.AddMinutes(2);
        await Assert.ThrowsAsync<MicrosoftAuthException>(() => _sessions.GetAsync(Steve));
        _now = Start.AddMinutes(3).AddSeconds(1);
        Assert.NotNull(await _sessions.GetAsync(Steve));
        Assert.Null(_sessions.BlockedUntil(Steve));
    }

    [Theory]
    [InlineData(null, 600)]      // no Retry-After: 10 minutes
    [InlineData(5, 60)]          // too short: at least a minute
    [InlineData(300, 300)]       // honoured as sent
    [InlineData(86400, 3600)]    // absurd: at most an hour
    public void Backoff_is_Retry_After_within_bounds(int? retryAfterSeconds, int expectedSeconds)
    {
        var asked = retryAfterSeconds is { } s ? TimeSpan.FromSeconds(s) : (TimeSpan?)null;
        Assert.Equal(TimeSpan.FromSeconds(expectedSeconds), MinecraftSessions.BackoffFor(asked));
    }

    [Fact]
    public async Task A_429_on_one_account_doesnt_block_another()
    {
        _vault.Add(Steve, "rt-s");
        _vault.Add(Alex, "rt-a");
        _sessions.NoteRateLimited(Steve, null);

        await Assert.ThrowsAsync<MicrosoftAuthException>(() => _sessions.GetAsync(Steve));
        Assert.NotNull(await _sessions.GetAsync(Alex));
    }

    [Fact]
    public async Task A_cached_session_still_plays_while_sign_ins_are_held_off()
    {
        _vault.Add(Steve, "rt-0", SessionFor(Steve, Start.AddHours(20)));
        _sessions.NoteRateLimited(Steve, null);

        Assert.Equal("saved", (await _sessions.ForPlayAsync(Steve))!.AccessToken);
    }

    // --- adding an account ------------------------------------------------------

    [Fact]
    public async Task An_added_accounts_session_is_reused_by_the_first_Play()
    {
        var added = SessionFor(Steve, Start.AddHours(24), token: "from-add");
        _vault.Add(Steve, "rt-0", added);
        _sessions.Adopt(added);

        Assert.Equal("from-add", (await _sessions.ForPlayAsync(Steve))!.AccessToken);
        Assert.Equal(0, _signIn.Calls);
    }

    [Fact]
    public async Task A_pending_account_finishes_later_and_takes_its_real_id()
    {
        // Added while Microsoft was rate-limiting: the Microsoft refresh token
        // is kept, the Minecraft step waits out the back-off.
        const string pending = "pending-1";
        _vault.Add(pending, "rt-0");
        _sessions.NoteRateLimited(pending, null);

        var tooSoon = await Assert.ThrowsAsync<MicrosoftAuthException>(() => _sessions.GetAsync(pending));
        Assert.True(tooSoon.RateLimited);
        Assert.Equal(0, _signIn.Calls);

        _now = Start.AddMinutes(11);
        var session = await _sessions.GetAsync(pending);

        Assert.Equal(Steve, session!.Uuid);
        Assert.False(_vault.Accounts.ContainsKey(pending));
        Assert.Equal("rt-1", _vault.Accounts[Steve].Refresh);
        // ...and Play under the real id reuses it.
        await _sessions.ForPlayAsync(Steve);
        Assert.Equal(1, _signIn.Calls);
    }

    [Fact]
    public async Task A_sign_in_that_comes_back_as_another_account_is_refused_and_saves_nothing()
    {
        // Alex's row holds a refresh token that signs in as Steve (crossed by an
        // older launcher). Nothing may be saved or launched as the wrong player.
        _vault.Add(Alex, "rt-0");

        var ex = await Assert.ThrowsAsync<MicrosoftAuthException>(() => _sessions.ForPlayAsync(Alex));

        Assert.Equal(MicrosoftAuthException.AccountMismatchStage, ex.Stage);
        Assert.Null(_vault.Accounts[Alex].Refresh); // unlinked: the token was Steve's
        Assert.Null(_vault.Accounts[Alex].Session);
        Assert.False(_vault.Accounts.ContainsKey(Steve));
        Assert.Equal(0, _vault.UsedMarks);
    }

    [Fact]
    public async Task A_refused_crossed_account_fails_fast_without_signing_in_again()
    {
        // Every retry used to rerun the whole chain (login_with_xbox is
        // rate-limited). After one refusal the row is unlinked: the next Play
        // and Friends calls answer "re-add" with no network at all.
        _vault.Add(Alex, "rt-0");
        await Assert.ThrowsAsync<MicrosoftAuthException>(() => _sessions.ForPlayAsync(Alex));

        Assert.Null(await _sessions.ForPlayAsync(Alex));
        Assert.Null(await _sessions.GetAsync(Alex));
        Assert.Equal(1, _signIn.Calls);
    }

    [Fact]
    public async Task Adding_the_account_again_during_a_refused_sign_in_keeps_the_new_sign_in()
    {
        // The re-add (AddSignedIn) runs outside the sign-in gate: it can land
        // while the crossed sign-in is still in flight. Its fresh tokens and
        // adopted session must survive that sign-in's refusal.
        _vault.Add(Alex, "rt-0");
        _signIn.Hold = new TaskCompletionSource();
        var play = _sessions.ForPlayAsync(Alex);
        await _signIn.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5));

        _now = Start.AddSeconds(5);
        var readded = SessionFor(Alex, Start.AddHours(24), token: "from-add");
        _vault.Add(Alex, "rt-a-fresh", readded);
        _sessions.Adopt(readded);
        _signIn.Hold.SetResult();

        var ex = await Assert.ThrowsAsync<MicrosoftAuthException>(() => play);
        Assert.Equal(MicrosoftAuthException.AccountMismatchStage, ex.Stage);
        Assert.Equal("rt-a-fresh", _vault.Accounts[Alex].Refresh);
        Assert.Same(readded, _vault.Accounts[Alex].Session);
        Assert.Equal("from-add", (await _sessions.ForPlayAsync(Alex))!.AccessToken);
        Assert.Equal(1, _signIn.Calls);
    }

    [Fact]
    public async Task A_failed_unlink_still_reports_the_mismatch()
    {
        _vault.Add(Alex, "rt-0");
        _vault.UnlinkFails = true;

        var ex = await Assert.ThrowsAsync<MicrosoftAuthException>(() => _sessions.ForPlayAsync(Alex));

        Assert.Equal(MicrosoftAuthException.AccountMismatchStage, ex.Stage);
        Assert.Contains("IOException", ex.Detail);
    }

    // --- fakes -----------------------------------------------------------------

    private sealed class FakeVault : ISessionVault
    {
        public sealed class Entry
        {
            public required string? Refresh { get; set; }
            public MinecraftSession? Session { get; set; }
        }

        public Dictionary<string, Entry> Accounts { get; } = new();
        public int UsedMarks { get; private set; }

        public void Add(string id, string refresh, MinecraftSession? session = null) =>
            Accounts[id] = new Entry { Refresh = refresh, Session = session };

        public string? RefreshToken(string accountId) =>
            Accounts.TryGetValue(accountId, out var e) ? e.Refresh : null;

        public MinecraftSession? SavedSession(string accountId) =>
            Accounts.TryGetValue(accountId, out var e) ? e.Session : null;

        public bool UnlinkFails;

        // Same compare-and-swap as the real vault: only while the row still
        // holds the token the refused sign-in used.
        public void Unlink(string accountId, string usedRefreshToken)
        {
            if (UnlinkFails) throw new IOException("accounts.json is locked");
            if (Accounts.TryGetValue(accountId, out var e) && e.Refresh == usedRefreshToken)
            {
                e.Refresh = null;
                e.Session = null;
            }
        }

        public void Save(string accountId, SilentSignIn signIn)
        {
            if (!Accounts.Remove(accountId, out var entry)) return;
            entry.Refresh = signIn.RefreshToken;
            entry.Session = signIn.Session;
            Accounts[signIn.Session.Uuid] = entry; // a pending account takes its real id
        }

        public void Forget(string accountId)
        {
            if (Accounts.TryGetValue(accountId, out var e)) e.Session = null;
        }

        public void MarkUsed(string accountId) => UsedMarks++;
    }

    private sealed class FakeSignIn : ISessionSignIn
    {
        private readonly Func<DateTimeOffset> _clock;
        private int _rotation;

        public FakeSignIn(Func<DateTimeOffset> clock) => _clock = clock;

        public int Calls;
        public int Checks;
        public MicrosoftAuthException? Fail;
        public TokenCheck CheckAnswer = TokenCheck.Valid;
        public TaskCompletionSource? Hold;
        public readonly TaskCompletionSource Entered = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public async Task<SilentSignIn> SignInAsync(string refreshToken, CancellationToken ct)
        {
            Interlocked.Increment(ref Calls);
            Entered.TrySetResult();
            if (Hold != null) await Hold.Task;
            if (Fail != null) throw Fail;
            var n = Interlocked.Increment(ref _rotation);
            // Every fake account is Steve once signed in (the pending test relies on it),
            // except the one whose refresh token says otherwise.
            var uuid = refreshToken == "rt-a" || refreshToken.StartsWith("rt-a-") ? Alex : Steve;
            var next = uuid == Alex ? $"rt-a-{n}" : $"rt-{n}";
            return new SilentSignIn(
                new MinecraftSession(uuid == Steve ? "Steve" : "Alex", uuid, $"token-{n}", "xuid", _clock().AddHours(24)),
                next);
        }

        public Task<TokenCheck> CheckAsync(string accessToken, CancellationToken ct)
        {
            Interlocked.Increment(ref Checks);
            return Task.FromResult(CheckAnswer);
        }
    }
}
