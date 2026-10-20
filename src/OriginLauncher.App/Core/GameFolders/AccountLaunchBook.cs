namespace OriginLauncher.App.Core.GameFolders;

/// <summary>Where one account's game is in its life: nothing, being prepared, booting, or open.</summary>
public enum GamePhase
{
    Idle,
    Preparing, // signing in, downloading, provisioning (the loading scene)
    Starting,  // the process is up, Minecraft's window isn't yet
    Running,   // Minecraft's window is open
}

public enum LaunchTone
{
    Neutral,
    Success,
    Warning,
    Error,
}

/// <summary>A status-pill line remembered per account, so switching accounts shows each one's own state.</summary>
public sealed record LaunchNote(string Text, LaunchTone Tone);

/// <summary>What the Play button does when clicked.</summary>
public enum PlayAction
{
    Launch,
    FocusGame,
    None,
}

/// <summary>How the Play button looks for the selected account.</summary>
public readonly record struct PlayButtonView(bool Enabled, bool Spinner, bool ShowsRunning);

/// <summary>One launch being prepared. Owns its cancellation source; the launch that created it disposes it.</summary>
public sealed class LaunchTicket
{
    internal LaunchTicket(string key, string version, long id)
    {
        Key = key;
        Version = version;
        Id = id;
    }

    public string Key { get; }
    public string Version { get; }
    public long Id { get; }
    public CancellationTokenSource Source { get; } = new();
    public CancellationToken Token => Source.Token;
}

// Several games at once, one per account (Will, 2026-10-12). This is the whole
// launch state machine, kept free of WPF and processes so it is unit tested
// (src/OriginLauncher.Tests/GameFoldersTests.cs). HomePage drives it from
// the UI thread; it locks anyway because process-exit callbacks arrive on the
// thread pool.
//
//   Idle --BeginPrepare--> Preparing --GameStarted--> Starting --WindowShown--> Running
//     ^                       |                          |                        |
//     +------EndPrepare-------+  (cancelled / failed)    +--------Exited----------+
//
// Rules:
//  * one game per account: BeginPrepare refuses while that account's game is
//    Starting or Running (the Play button then focuses that game instead);
//  * "the newest launch action cancels any in-flight one" holds PER ACCOUNT:
//    a new preparation cancels only the same account's earlier preparation —
//    never another account's preparation or running game.
public sealed class AccountLaunchBook
{
    private sealed class Entry
    {
        public GamePhase Phase;
        public string? Version;
        public LaunchTicket? Preparing;
        public object? Game;
        public LaunchNote? Note;
    }

    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private long _nextId;

    public GamePhase PhaseOf(string key)
    {
        lock (_gate) return _entries.TryGetValue(key, out var e) ? e.Phase : GamePhase.Idle;
    }

    /// <summary>The account's started game (whatever HomePage stored), or null.</summary>
    public object? GameOf(string key)
    {
        lock (_gate) return _entries.TryGetValue(key, out var e) ? e.Game : null;
    }

    /// <summary>The version the account is preparing or playing, or null when idle.</summary>
    public string? VersionOf(string key)
    {
        lock (_gate) return _entries.TryGetValue(key, out var e) && e.Phase != GamePhase.Idle ? e.Version : null;
    }

    /// <summary>Every account whose game is starting or running.</summary>
    public IReadOnlyList<string> PlayingKeys()
    {
        lock (_gate)
            return _entries.Where(kv => kv.Value.Phase is GamePhase.Starting or GamePhase.Running)
                .Select(kv => kv.Key).ToList();
    }

    /// <summary>
    /// Starts preparing a launch for an account. Null when that account already
    /// has a game starting or running. Cancels only the same account's earlier
    /// preparation, if any.
    /// </summary>
    public LaunchTicket? BeginPrepare(string key, string version)
    {
        lock (_gate)
        {
            var entry = EntryFor(key);
            if (entry.Phase is GamePhase.Starting or GamePhase.Running) return null;
            entry.Preparing?.Source.Cancel();
            var ticket = new LaunchTicket(key, version, ++_nextId);
            entry.Preparing = ticket;
            entry.Phase = GamePhase.Preparing;
            entry.Version = version;
            entry.Note = null;
            return ticket;
        }
    }

    /// <summary>True while this ticket is still its account's newest launch action.</summary>
    public bool IsCurrent(LaunchTicket ticket)
    {
        lock (_gate)
            return _entries.TryGetValue(ticket.Key, out var e) && ReferenceEquals(e.Preparing, ticket);
    }

    /// <summary>Cancels the account's preparation, if one is in flight.</summary>
    public bool CancelPreparation(string key)
    {
        lock (_gate)
        {
            if (!_entries.TryGetValue(key, out var e) || e.Preparing == null) return false;
            e.Preparing.Source.Cancel();
            return true;
        }
    }

    /// <summary>The ticket's game process started. False (and nothing changes) when the ticket was superseded.</summary>
    public bool GameStarted(LaunchTicket ticket, object game)
    {
        lock (_gate)
        {
            if (!_entries.TryGetValue(ticket.Key, out var e) || !ReferenceEquals(e.Preparing, ticket)) return false;
            e.Preparing = null;
            e.Phase = GamePhase.Starting;
            e.Game = game;
            e.Version = ticket.Version;
            return true;
        }
    }

    /// <summary>
    /// The ticket's preparation ended without starting a game (cancelled or
    /// failed). Returns true when it was still current — the account is then idle.
    /// </summary>
    public bool EndPrepare(LaunchTicket ticket)
    {
        lock (_gate)
        {
            if (!_entries.TryGetValue(ticket.Key, out var e) || !ReferenceEquals(e.Preparing, ticket)) return false;
            e.Preparing = null;
            if (e.Phase == GamePhase.Preparing)
            {
                e.Phase = GamePhase.Idle;
                e.Version = null;
            }
            return true;
        }
    }

    /// <summary>Minecraft's window appeared (or the boot watch gave up looking): Starting -> Running.</summary>
    public bool WindowShown(string key, object game)
    {
        lock (_gate)
        {
            if (!_entries.TryGetValue(key, out var e) || !ReferenceEquals(e.Game, game) || e.Phase != GamePhase.Starting)
                return false;
            e.Phase = GamePhase.Running;
            return true;
        }
    }

    /// <summary>The account's game process exited: back to idle. False for a game that isn't the account's current one.</summary>
    public bool Exited(string key, object game)
    {
        lock (_gate)
        {
            if (!_entries.TryGetValue(key, out var e) || !ReferenceEquals(e.Game, game)) return false;
            e.Game = null;
            if (e.Phase is GamePhase.Starting or GamePhase.Running)
            {
                e.Phase = GamePhase.Idle;
                e.Version = null;
            }
            return true;
        }
    }

    public void SetNote(string key, LaunchNote? note)
    {
        lock (_gate) EntryFor(key).Note = note;
    }

    public LaunchNote? NoteOf(string key)
    {
        lock (_gate) return _entries.TryGetValue(key, out var e) ? e.Note : null;
    }

    private Entry EntryFor(string key)
    {
        if (!_entries.TryGetValue(key, out var entry))
            _entries[key] = entry = new Entry();
        return entry;
    }

    // ---- the Play button, as a pure function of the selected account's phase ----

    public static PlayAction ActionFor(GamePhase phase) => phase switch
    {
        GamePhase.Idle => PlayAction.Launch,
        GamePhase.Running => PlayAction.FocusGame,
        _ => PlayAction.None,
    };

    /// <param name="phase">The selected account's phase.</param>
    /// <param name="canPlay">An account is selected, or offline test mode is on.</param>
    /// <param name="hasVersion">A version is selected.</param>
    public static PlayButtonView ButtonFor(GamePhase phase, bool canPlay, bool hasVersion) => phase switch
    {
        GamePhase.Running => new PlayButtonView(true, false, true),
        GamePhase.Preparing or GamePhase.Starting => new PlayButtonView(false, true, false),
        _ => new PlayButtonView(canPlay && hasVersion, false, false),
    };
}
