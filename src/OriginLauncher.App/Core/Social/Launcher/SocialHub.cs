namespace OriginLauncher.App.Core.Social.Launcher;

/// <summary>
/// The launcher's one social service and the facts it shares between windows:
/// the version Home has selected (sent as presence) and the game the launcher
/// started (for the party leader's warp). Created on first use, started by
/// MainWindow once the first frame is up — never on the cold-start path.
/// </summary>
public static class SocialHub
{
    private static SocialService? _service;
    private static string? _presenceVersion;

    public static SocialService Service => _service ??= new SocialService(
        new SocialClient(SocialEndpoints.BaseUrl, SocialHttp.SendAsync, LauncherIdentity.GetAsync),
        () => Volatile.Read(ref _presenceVersion));

    /// <summary>The game this launcher started, and the server it's on (from its log).</summary>
    public static RunningGameTracker Game { get; } = new();

    /// <summary>Home's selected version, sent as the launcher's presence version.</summary>
    public static string? PresenceVersion
    {
        get => Volatile.Read(ref _presenceVersion);
        set => Volatile.Write(ref _presenceVersion, value);
    }

    /// <summary>Render mode: a fixed players-online number instead of the network.</summary>
    public static int? OnlineCountOverride { get; set; }
}
