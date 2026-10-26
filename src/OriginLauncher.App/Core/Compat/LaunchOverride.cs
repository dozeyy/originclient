namespace OriginLauncher.App.Core.Compat;

/// <summary>The ways a launch can be started on Home. Only some of them may override a mod refusal.</summary>
public enum LaunchRoute
{
    /// <summary>The Play button.</summary>
    Play,
    /// <summary>Enter (and the Import page's Play, which goes through Enter).</summary>
    EnterKey,
    /// <summary>A friend's Join / party warp: a launch the player did not look at the refusal for.</summary>
    JoinServer,
    /// <summary>The crash window's Retry.</summary>
    Retry,
}

/// <summary>
/// "Start anyway": the metadata can be wrong, so a player who has read why the mods were refused may press Play and
/// get the game. The permission is narrow on purpose (Will's review, 2026-10-25):
///   * for ONE account, ONE Minecraft version and ONE exact mod set (the refusal's set key);
///   * honoured only from Play and Enter, and only while that account's status line still shows
///     the refusal text (the player is looking at what they are overriding);
///   * used once: the launch that takes it spends it;
///   * gone as soon as the account or version changes, or the line moves on.
/// Join server and the crash Retry always check normally. Pure logic, no UI: <c>HomePage</c> owns one and asks it.
/// </summary>
public sealed class LaunchOverride
{
    private sealed record Refusal(string Account, string Version, string SetKey, string Text);

    private readonly object _lock = new();
    private Refusal? _refusal;

    /// <summary>A launch was refused for this set; <paramref name="shownText"/> is the line now on that account's Home.</summary>
    public void Refuse(string account, string version, string setKey, string shownText)
    {
        lock (_lock)
            _refusal = string.IsNullOrEmpty(setKey) ? null : new Refusal(account, version, setKey, shownText);
    }

    /// <summary>The selected account or version may have changed: a refusal for anything else is forgotten.</summary>
    public void ContextChanged(string? account, string? version)
    {
        lock (_lock)
            if (_refusal != null && (_refusal.Account != account || _refusal.Version != version))
                _refusal = null;
    }

    /// <summary>Forgets any refusal.</summary>
    public void Clear()
    {
        lock (_lock) _refusal = null;
    }

    /// <summary>
    /// The set key to pass to <see cref="LaunchPreflight.Run"/> for this launch, or null (check normally). Spends the
    /// refusal when it is honoured. A route that may not override leaves the refusal where it was; a stale one (other
    /// account, other version, or the line no longer shows it) is dropped.
    /// </summary>
    /// <param name="statusNow">The text on that account's status line right now, or null when it is not on screen.</param>
    public string? Accept(LaunchRoute route, string account, string version, string? statusNow)
    {
        if (route is not (LaunchRoute.Play or LaunchRoute.EnterKey)) return null;
        lock (_lock)
        {
            var r = _refusal;
            if (r == null) return null;
            if (r.Account != account || r.Version != version || statusNow != r.Text)
            {
                _refusal = null;
                return null;
            }
            _refusal = null;
            return r.SetKey;
        }
    }
}
