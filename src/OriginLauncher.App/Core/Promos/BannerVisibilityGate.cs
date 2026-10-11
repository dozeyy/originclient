namespace OriginLauncher.App.Core.Promos;

/// <summary>
/// THE single decision for "may the home banner do anything right now".
/// Rotation, Discord count refreshes, and every ad-network load/reload run
/// only while <see cref="IsActive"/> is true; the moment it turns false they
/// stop and the ad WebView is hidden and suspended.
///
/// Why this is strict: background ad impressions (an ad loading or refreshing
/// in a window nobody can see) are ad fraud — Feather was publicly accused of
/// exactly that. So the banner is active only while ALL of these hold:
///   - the player is on the Home page,
///   - the launcher window is not minimized,
///   - no launcher overlay (version picker, launch/update scene, account
///     panel, sign-in) covers Home,
///   - other windows cover less than half of the banner (Win32 z-order check,
///     <see cref="OcclusionMath"/>; a game on ANOTHER monitor doesn't count,
///     so the banner keeps running on a second screen while Minecraft plays),
///   - the Windows session is unlocked and awake.
///
/// Occlusion starts "unknown = covered": nothing runs until the first real
/// check says the banner is visible, and the flag resets to covered whenever
/// checking stops (minimized / off Home / locked), so returning always waits
/// for a fresh check instead of trusting a stale one.
/// </summary>
public sealed class BannerVisibilityGate
{
    private bool _onHomePage;
    private bool _windowMinimized;
    private bool _homeSceneOpen;
    private bool _chromeOverlayOpen;
    private bool _occluded = true;
    private bool _sessionInactive;
    private bool _lastActive;

    /// <summary>Raised (with the new value) only when <see cref="IsActive"/> actually flips.</summary>
    public event Action<bool>? ActiveChanged;

    public bool IsActive =>
        _onHomePage && !_windowMinimized && !_homeSceneOpen && !_chromeOverlayOpen
        && !_occluded && !_sessionInactive;

    /// <summary>
    /// Whether the occlusion poll should run. Not gated on in-app overlays: an
    /// overlay closing must find a fresh occlusion answer already in place.
    /// </summary>
    public bool ShouldWatchOcclusion => _onHomePage && !_windowMinimized && !_sessionInactive;

    public bool OnHomePage { get => _onHomePage; set => Set(ref _onHomePage, value); }
    public bool WindowMinimized { get => _windowMinimized; set => Set(ref _windowMinimized, value); }
    public bool HomeSceneOpen { get => _homeSceneOpen; set => Set(ref _homeSceneOpen, value); }
    public bool ChromeOverlayOpen { get => _chromeOverlayOpen; set => Set(ref _chromeOverlayOpen, value); }
    public bool Occluded { get => _occluded; set => Set(ref _occluded, value); }
    public bool SessionInactive { get => _sessionInactive; set => Set(ref _sessionInactive, value); }

    private void Set(ref bool field, bool value)
    {
        if (field == value) return;
        field = value;

        // Stopped watching → forget the last answer (see class comment).
        if (!ShouldWatchOcclusion) _occluded = true;

        var active = IsActive;
        if (active == _lastActive) return;
        _lastActive = active;
        ActiveChanged?.Invoke(active);
    }
}
