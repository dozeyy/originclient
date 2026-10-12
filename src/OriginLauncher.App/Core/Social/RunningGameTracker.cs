using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Social;

/// <summary>
/// What the game the launcher started is doing, read from its own console
/// output (HomePage already captures it to the launch log). The launcher's
/// only use is the party leader's "Warp party": the warp needs the server the
/// leader is on, and the launcher's own presence is just "menu".
///
/// Minecraft logs "Connecting to host, port" when it joins a server (every
/// version Origin ships) and "Starting integrated minecraft server" for
/// singleplayer. Leaving a server for the title screen isn't logged, so the
/// last server sticks until another world, server or the game's exit.
/// </summary>
public sealed partial class RunningGameTracker
{
    public sealed record GameState(int Id, string Version, string? Server);

    private readonly object _gate = new();
    private GameState? _state;
    private int _nextId;

    public event Action? Changed;

    public GameState? Current
    {
        get { lock (_gate) return _state; }
    }

    /// <summary>A game process started; returns the id its output is reported under.</summary>
    public int Started(string version)
    {
        int id;
        lock (_gate)
        {
            id = ++_nextId;
            _state = new GameState(id, version, null);
        }
        Changed?.Invoke();
        return id;
    }

    public void Observe(int id, string? line)
    {
        if (string.IsNullOrEmpty(line)) return;
        var connect = ParseConnect(line);
        var singleplayer = connect == null && IsSingleplayerStart(line);
        if (connect == null && !singleplayer) return;

        lock (_gate)
        {
            if (_state is not { } s || s.Id != id) return; // an older game still running
            _state = s with { Server = connect };
        }
        Changed?.Invoke();
    }

    public void Exited(int id)
    {
        lock (_gate)
        {
            if (_state is not { } s || s.Id != id) return;
            _state = null;
        }
        Changed?.Invoke();
    }

    /// <summary>"... Connecting to mc.hypixel.net, 25565" → "mc.hypixel.net" (other ports kept).</summary>
    public static string? ParseConnect(string line)
    {
        var m = ConnectLine().Match(line);
        if (!m.Success) return null;
        var host = m.Groups["host"].Value;
        var port = m.Groups["port"].Value;
        var address = port == "25565" ? host : host.Contains(':') ? $"[{host}]:{port}" : $"{host}:{port}";
        return JoinArguments.TryParseServer(address, out _, out _) ? address : null;
    }

    public static bool IsSingleplayerStart(string line) =>
        line.Contains("Starting integrated minecraft server", StringComparison.Ordinal);

    [GeneratedRegex(@"Connecting to (?<host>[^\s,]+), (?<port>\d{1,5})\s*$")]
    private static partial Regex ConnectLine();
}
