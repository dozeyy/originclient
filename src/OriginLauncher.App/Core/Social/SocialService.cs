namespace OriginLauncher.App.Core.Social;

/// <summary>
/// The launcher's side of Origin social: one background loop that signs in
/// and polls /v1/sync on the budget in <see cref="SyncSchedule"/>, folds each
/// answer into a <see cref="SocialSnapshot"/>, and raises toasts — plus the
/// drawer's actions (add, accept, chat, party ...).
///
/// Threading: everything network runs on the thread pool; <see cref="Changed"/>
/// and <see cref="ToastRaised"/> fire on a pool thread, so the UI marshals
/// them itself. Nothing here ever throws into the caller: failures become a
/// status (and a retry with backoff) or an action's error sentence.
/// </summary>
public sealed class SocialService : IDisposable
{
    private const string GenericError = "Something went wrong. Try again in a moment.";

    private readonly SocialClient _client;
    private readonly Func<string?> _presenceVersion;
    private readonly Func<DateTimeOffset> _now;
    private readonly object _gate = new();
    private readonly SemaphoreSlim _wake = new(0, int.MaxValue);

    private SocialSnapshot _snapshot = new();
    private CancellationTokenSource? _loop;
    private bool _demo;

    // Sync state for the current account; reset by ResetAccount.
    private string _cursor = "0";
    private bool _synced;
    private int _generation;
    private int _failures;
    private int _authFailures;

    // What the schedule depends on.
    private bool _drawerOpen;
    private string? _openConversation;
    private DateTimeOffset? _inactiveSince;
    private DateTimeOffset? _lastLiveEvent;

    public SocialService(SocialClient client, Func<string?> presenceVersion, Func<DateTimeOffset>? now = null)
    {
        _client = client;
        _presenceVersion = presenceVersion;
        _now = now ?? (() => DateTimeOffset.UtcNow);
    }

    /// <summary>A new snapshot is current. Fires on a pool thread.</summary>
    public event Action<SocialSnapshot>? Changed;

    /// <summary>Something worth a toast arrived. Fires on a pool thread.</summary>
    public event Action<SocialToast>? ToastRaised;

    public SocialSnapshot Snapshot
    {
        get { lock (_gate) return _snapshot; }
    }

    /// <summary>Starts the sync loop (idempotent). Returns at once — all work is in the background.</summary>
    public void Start()
    {
        CancellationToken token;
        lock (_gate)
        {
            if (_loop != null || _demo) return;
            _loop = new CancellationTokenSource();
            token = _loop.Token;
        }
        _ = Task.Run(() => LoopAsync(token));
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _loop?.Cancel();
            _loop = null;
        }
    }

    /// <summary>Sync now instead of at the next scheduled tick.</summary>
    public void Poke()
    {
        if (_wake.CurrentCount == 0) _wake.Release();
    }

    public void SetDrawerOpen(bool open)
    {
        lock (_gate) _drawerOpen = open;
        if (open) Poke();
    }

    /// <summary>The launcher window gained or lost focus (or was minimised).</summary>
    public void SetAppActive(bool active)
    {
        bool wasBackground;
        lock (_gate)
        {
            var now = _now();
            wasBackground = _inactiveSince is { } since && now - since > SyncSchedule.InactiveAfter;
            _inactiveSince = active ? null : _inactiveSince ?? now;
        }
        // Back from a long absence: catch up now rather than in up to 60 s.
        if (active && wasBackground) Poke();
    }

    /// <summary>The chat pane now shows this conversation (a uuid, PartyKey, or null): it's read.</summary>
    public void SetOpenConversation(string? key)
    {
        SocialSnapshot snapshot;
        lock (_gate)
        {
            _openConversation = key;
            _snapshot = SocialMerge.MarkRead(_snapshot, key);
            snapshot = _snapshot;
        }
        Changed?.Invoke(snapshot);
    }

    /// <summary>The selected account changed: forget the old sign-in and data, start over.</summary>
    public void ResetAccount()
    {
        SocialSnapshot snapshot;
        lock (_gate)
        {
            if (_demo) return;
            _generation++;
            _cursor = "0";
            _synced = false;
            _failures = 0;
            _authFailures = 0;
            _lastLiveEvent = null;
            _snapshot = new SocialSnapshot();
            snapshot = _snapshot;
        }
        _client.Forget();
        Changed?.Invoke(snapshot);
        Poke();
    }

    /// <summary>Render/preview mode: show this snapshot, never touch the network.</summary>
    public void ShowDemo(SocialSnapshot snapshot)
    {
        lock (_gate)
        {
            _demo = true;
            _loop?.Cancel();
            _loop = null;
            _snapshot = snapshot;
        }
        Changed?.Invoke(snapshot);
    }

    // ---- the loop ------------------------------------------------------------

    private async Task LoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TimeSpan delay;
            try
            {
                delay = await TickAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception)
            {
                // Never let one bad answer end social for the session.
                delay = SyncSchedule.Backoff(++_failures);
            }

            try
            {
                await _wake.WaitAsync(delay, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private async Task<TimeSpan> TickAsync(CancellationToken ct)
    {
        int generation;
        string cursor;
        string? etag;
        lock (_gate)
        {
            generation = _generation;
            cursor = _cursor;
            etag = _snapshot.FriendsEtag;
        }

        var auth = await _client.EnsureAuthAsync(ct).ConfigureAwait(false);
        if (auth.State != SocialAuthState.Ok)
        {
            var (status, delay) = auth.State switch
            {
                SocialAuthState.NoAccount => (SocialStatus.NotSignedIn, SyncSchedule.SignedOutRecheck),
                SocialAuthState.SignInFailed => (SocialStatus.SignInFailed, SyncSchedule.AuthBackoff(++_authFailures)),
                _ => (SocialStatus.Unreachable, SyncSchedule.Backoff(++_failures)),
            };
            Publish(generation, s => s with { Status = status, StatusMessage = auth.Message });
            return delay;
        }
        _authFailures = 0;

        var response = await _client.PostAsync("/v1/sync", SocialJson.SyncBody(cursor, _presenceVersion(), etag), ct)
            .ConfigureAwait(false);
        var sync = response.Ok ? SocialJson.ParseSync(response.Body) : null;
        if (sync == null)
        {
            Publish(generation, s => s with { Status = SocialStatus.Unreachable, StatusMessage = null });
            return SyncSchedule.Backoff(++_failures);
        }
        _failures = 0;

        SocialSnapshot snapshot;
        SocialMergeOutcome merged;
        SyncInputs inputs;
        lock (_gate)
        {
            if (generation != _generation) return TimeSpan.Zero; // account switched mid-flight
            merged = SocialMerge.Apply(_snapshot, sync, firstSync: !_synced, _openConversation, _drawerOpen);
            _snapshot = snapshot = merged.Snapshot with { Status = SocialStatus.Online, StatusMessage = null, HasData = true };
            _cursor = sync.CursorJson ?? _cursor;
            _synced = true;
            if (merged.LiveEvent) _lastLiveEvent = _now();
            inputs = new SyncInputs(_now(), _drawerOpen, snapshot.Party != null, _lastLiveEvent, _inactiveSince);
        }

        Changed?.Invoke(snapshot);
        foreach (var toast in merged.Toasts) ToastRaised?.Invoke(toast);
        return SyncSchedule.NextDelay(inputs);
    }

    private void Publish(int generation, Func<SocialSnapshot, SocialSnapshot> change)
    {
        SocialSnapshot snapshot;
        lock (_gate)
        {
            if (generation != _generation) return;
            _snapshot = snapshot = change(_snapshot);
        }
        Changed?.Invoke(snapshot);
    }

    // ---- actions (each returns ok, or the sentence to show) -----------------------

    public Task<SocialActionResult> SetAppearOfflineAsync(bool appearOffline) =>
        ActAsync("/v1/settings", SocialJson.Body(("appearOffline", appearOffline)));

    public Task<SocialActionResult> AddFriendAsync(string name)
    {
        var trimmed = name.Trim();
        if (!SocialText.IsValidUsername(trimmed))
            return Task.FromResult(SocialActionResult.Fail("That isn't a Minecraft username."));
        if (string.Equals(trimmed, Snapshot.Me?.Name, StringComparison.OrdinalIgnoreCase))
            return Task.FromResult(SocialActionResult.Fail("That's you!"));
        return ActAsync("/v1/friends/request", SocialJson.Body(("name", trimmed)));
    }

    public Task<SocialActionResult> RespondAsync(string uuid, bool accept) =>
        ActAsync("/v1/friends/respond", SocialJson.Body(("uuid", uuid), ("accept", accept)));

    /// <summary>Removes a friend — and cancels an outgoing request (the contract has no separate cancel).</summary>
    public Task<SocialActionResult> RemoveFriendAsync(string uuid) =>
        ActAsync("/v1/friends/remove", SocialJson.Body(("uuid", uuid)));

    public Task<SocialActionResult> BlockAsync(string uuid) =>
        ActAsync("/v1/friends/block", SocialJson.Body(("uuid", uuid)));

    public Task<SocialActionResult> UnblockAsync(string uuid) =>
        ActAsync("/v1/friends/unblock", SocialJson.Body(("uuid", uuid)));

    public Task<SocialActionResult> SendMessageAsync(string to, string text)
    {
        var trimmed = text.Trim();
        if (trimmed.Length == 0) return Task.FromResult(SocialActionResult.Fail(""));
        if (trimmed.Length > SocialText.MaxMessageLength)
            return Task.FromResult(SocialActionResult.Fail($"Messages can be up to {SocialText.MaxMessageLength} characters."));
        return ActAsync("/v1/messages/send", SocialJson.Body(("to", to), ("text", trimmed)));
    }

    /// <summary>Invites a friend; the service starts a party (you lead) when you aren't in one.</summary>
    public Task<SocialActionResult> InviteToPartyAsync(string uuid) =>
        ActAsync("/v1/party/invite", SocialJson.Body(("uuid", uuid)));

    public Task<SocialActionResult> AcceptPartyAsync(string partyId) =>
        ActAsync("/v1/party/accept", SocialJson.Body(("partyId", partyId)));

    public Task<SocialActionResult> DeclinePartyAsync(string partyId) =>
        ActAsync("/v1/party/decline", SocialJson.Body(("partyId", partyId)));

    public Task<SocialActionResult> LeavePartyAsync() => ActAsync("/v1/party/leave", "{}");

    public Task<SocialActionResult> KickAsync(string uuid) =>
        ActAsync("/v1/party/kick", SocialJson.Body(("uuid", uuid)));

    public Task<SocialActionResult> PromoteAsync(string uuid) =>
        ActAsync("/v1/party/promote", SocialJson.Body(("uuid", uuid)));

    public Task<SocialActionResult> PartyChatAsync(string text)
    {
        var trimmed = text.Trim();
        if (trimmed.Length == 0) return Task.FromResult(SocialActionResult.Fail(""));
        if (trimmed.Length > SocialText.MaxMessageLength)
            return Task.FromResult(SocialActionResult.Fail($"Messages can be up to {SocialText.MaxMessageLength} characters."));
        return ActAsync("/v1/party/chat", SocialJson.Body(("text", trimmed)));
    }

    public Task<SocialActionResult> WarpAsync(string server, string version) =>
        ActAsync("/v1/party/warp", SocialJson.Body(("server", server), ("version", version)));

    private async Task<SocialActionResult> ActAsync(string path, string body, bool poke = true)
    {
        lock (_gate)
        {
            if (_demo) return SocialActionResult.Fail("Preview only.");
        }
        try
        {
            var response = await _client.PostAsync(path, body, CancellationToken.None).ConfigureAwait(false);
            if (poke) Poke();
            return response.Ok ? SocialActionResult.Success : SocialActionResult.Fail(response.ErrorText);
        }
        catch (Exception)
        {
            return SocialActionResult.Fail(GenericError);
        }
    }
}
