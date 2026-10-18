using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Launch;

/// <summary>Where one starting game is, as far as the launcher can tell.</summary>
public enum HandoffStage
{
    /// <summary>The process is up; Minecraft's window isn't yet.</summary>
    Starting,
    /// <summary>Minecraft's window exists, the Origin loading screen isn't on it yet.</summary>
    Opening,
    /// <summary>The game is drawing the Origin loading screen: it has taken over from the launcher.</summary>
    Loading,
    /// <summary>The loading screen is gone (title screen, or straight into a Quick Play world).</summary>
    Ready,
}

/// <summary>
/// Where the game opened its (still transparent) window, in screen pixels: the
/// client area, not the frame. <paramref name="GuiScale"/> is the game's GUI
/// scale (0: unknown, use Minecraft's auto rule), so the launcher can lay its
/// scene out exactly as the game will draw it.
/// </summary>
public readonly record struct GameWindowRect(int X, int Y, int Width, int Height, int GuiScale);

/// <summary>What the launcher should do right now for one starting game.</summary>
/// <param name="Stage">The furthest stage reached.</param>
/// <param name="ReleaseScene">Lower the launcher's loading scene: the game has taken over (or never will say so).</param>
/// <param name="GameReady">The account's game counts as running (Play button stops spinning).</param>
/// <param name="Window">The game announced its window: glide the scene onto it (null: older build, or fullscreen).</param>
public readonly record struct HandoffView(HandoffStage Stage, bool ReleaseScene, bool GameReady, GameWindowRect? Window = null);

/// <summary>
/// The launcher-to-game startup handshake. The launcher passes a fresh random
/// token as <c>-Dorigin.handoff=&lt;token&gt;</c>; an Origin build that speaks
/// the handshake prints <c>origin-handoff &lt;token&gt; &lt;stage&gt;</c> on its
/// stdout (hello at pre-launch, loading on its first loading-screen frame,
/// ready once the loading screen is gone), which the launcher already
/// reads into the launch log. With <see cref="WindowArgument"/> the game also
/// opens its window invisibly, centred where the launcher asked, and says
/// <c>window x,y,w,h,scale</c>: the launcher glides its scene onto that rect
/// and the window fades in over it, saying <c>loading</c> once fully shown. No socket, pipe or file: nothing outside the
/// game process can reach it, and the per-launch token keeps any other text in
/// the log (a server's chat line, another mod) from counterfeiting a stage.
///
/// Every stage has a fallback, so a build that never speaks it (1.8.9, older
/// jars, a version not yet wired) behaves like before: the window appearing
/// with no hello means "no handshake", and fixed ceilings stop anything from
/// waiting forever. The ceilings are only fallbacks — the markers drive it.
///
/// Pure and thread-safe: <see cref="Observe"/> runs on the stdout reader
/// thread, the rest on the UI thread. Time is passed in, so tests drive it.
/// </summary>
public sealed class GameHandoff
{
    public const string Property = "origin.handoff";
    public const string Marker = "origin-handoff";
    public const string WindowProperty = "origin.handoff.window";

    /// <summary>Window up but no hello this long after: the build doesn't speak the handshake.</summary>
    public static readonly TimeSpan HelloGrace = TimeSpan.FromSeconds(3);
    /// <summary>Hello seen, window up, but no loading frame this long after: lower the scene anyway.</summary>
    public static readonly TimeSpan LoadingCeiling = TimeSpan.FromSeconds(20);
    /// <summary>From process start: count the game as running whatever was (not) heard — the old boot-watch ceiling.</summary>
    public static readonly TimeSpan ReadyCeiling = TimeSpan.FromSeconds(90);

    private readonly object _gate = new();
    private DateTime? _startedAt;
    private DateTime? _windowAt;
    private bool _hello;
    private GameWindowRect? _window;
    private HandoffStage _reported = HandoffStage.Starting;

    /// <summary>Made before provisioning (the token goes into the JVM arguments); the clock starts at <see cref="ProcessStarted"/>.</summary>
    public GameHandoff(string? token = null)
    {
        Token = token ?? NewToken();
    }

    public string Token { get; }

    /// <summary>The JVM argument that hands the token to the game.</summary>
    public string JvmArgument => $"-D{Property}={Token}";

    /// <summary>Asks the game to open its window centred on this screen point (pixels), hidden until the handoff.</summary>
    public static string WindowArgument(int centerX, int centerY) => $"-D{WindowProperty}={centerX},{centerY}";

    public static string NewToken() => Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();

    /// <summary>
    /// One line of the game's output. True when it advanced the handshake (so
    /// the caller knows to re-poll on the UI thread); false for every other line.
    /// </summary>
    public bool Observe(string? line)
    {
        if (line is null) return false;
        var stage = Parse(line, Token);
        if (stage is null) return false;
        lock (_gate)
        {
            if (stage == "window")
            {
                if (_window != null || ParseWindow(line, Token) is not { } rect) return false;
                _window = rect;
                _hello = true;
                return true;
            }
            if (stage == "hello")
            {
                if (_hello) return false;
                _hello = true;
                return true;
            }
            var next = stage switch
            {
                "loading" => HandoffStage.Loading,
                "ready" => HandoffStage.Ready,
                _ => (HandoffStage?)null,
            };
            if (next is not { } n || n <= _reported) return false;
            _hello = true; // a later stage implies the build speaks it
            _reported = n;
            return true;
        }
    }

    /// <summary>The game process started: the ready ceiling counts from here, not from provisioning.</summary>
    public void ProcessStarted(DateTime now)
    {
        lock (_gate) _startedAt ??= now;
    }

    /// <summary>Minecraft's window was seen (the first time counts).</summary>
    public void WindowSeen(DateTime now)
    {
        lock (_gate) _windowAt ??= now;
    }

    public HandoffView Poll(DateTime now)
    {
        lock (_gate)
        {
            var stage = _reported;
            if (stage == HandoffStage.Starting && _windowAt != null) stage = HandoffStage.Opening;

            var sinceWindow = _windowAt is { } w ? now - w : (TimeSpan?)null;
            var pastReadyCeiling = _startedAt is { } s && now - s >= ReadyCeiling;
            // A window with no hello after the grace: an older/unwired build.
            // It behaves exactly like the old launcher: the window is the signal.
            var silentBuild = !_hello && sinceWindow >= HelloGrace;

            var release = stage >= HandoffStage.Loading
                          || silentBuild
                          || sinceWindow >= LoadingCeiling
                          || pastReadyCeiling;
            var ready = stage == HandoffStage.Ready || silentBuild || pastReadyCeiling;
            return new HandoffView(stage, release, ready, _window);
        }
    }

    /// <summary>The rect of a <c>window x,y,w,h,scale</c> marker carrying this token, or null if malformed.</summary>
    public static GameWindowRect? ParseWindow(string line, string token)
    {
        var probe = Marker + " " + token + " window ";
        var at = line.IndexOf(probe, StringComparison.Ordinal);
        if (at < 0) return null;
        var rest = line[(at + probe.Length)..];
        var space = rest.IndexOfAny([' ', '\r', '\n', '\t']);
        if (space >= 0) rest = rest[..space];
        var parts = rest.Split(',');
        if (parts.Length != 5) return null;
        var n = new int[5];
        for (var i = 0; i < 5; i++)
            if (!int.TryParse(parts[i], System.Globalization.NumberStyles.AllowLeadingSign,
                    System.Globalization.CultureInfo.InvariantCulture, out n[i])) return null;
        // Anything a real monitor layout can't produce is not the game talking.
        if (Math.Abs(n[0]) > 100_000 || Math.Abs(n[1]) > 100_000) return null;
        if (n[2] is < 1 or > 16_384 || n[3] is < 1 or > 16_384 || n[4] is < 0 or > 16) return null;
        return new GameWindowRect(n[0], n[1], n[2], n[3], n[4]);
    }

    /// <summary>The stage word of a marker line carrying this token, or null.</summary>
    public static string? Parse(string line, string token)
    {
        // The game logs through log4j, so the marker sits after a "[time]
        // [thread/INFO]: " prefix; match the token-bearing phrase anywhere.
        var probe = Marker + " " + token + " ";
        var at = line.IndexOf(probe, StringComparison.Ordinal);
        if (at < 0) return null;
        var rest = line.AsSpan(at + probe.Length);
        var end = 0;
        while (end < rest.Length && char.IsAsciiLetterLower(rest[end])) end++;
        return end == 0 ? null : rest[..end].ToString();
    }
}
