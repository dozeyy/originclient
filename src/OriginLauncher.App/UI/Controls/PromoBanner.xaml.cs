using System.IO;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using Microsoft.Win32;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Promos;

namespace OriginLauncher.App.UI.Controls;

// ============================================================================
// HOME BANNER — the slim row under the Play card.
//
// Content: the remote promo feed (Core/Promos/PromoFeedLoader: live site →
// raw GitHub → on-disk cache → built-in), refreshed at startup and every 30
// minutes. Two modes:
//   - House promos: Will's own cards plus a live Discord card (server name,
//     icon, online/member counts), rotating every "rotateSeconds".
//   - Ad network ("network.enabled" in the feed): the feed's page hosted in a
//     dedicated, always-muted WebView2 (BannerAdHost). Falls back to house
//     promos on any failure, retrying after 10 minutes.
//
// VISIBILITY GATING — mandatory, and the reason this file is shaped the way
// it is. Ads loading or refreshing where nobody can see them are fraudulent
// impressions (Feather was publicly accused of exactly this), so the banner
// does NOTHING — no rotation, no Discord refresh, no ad load/reload, ad
// WebView collapsed (controller IsVisible = false) and suspended — unless
// Core/Promos/BannerVisibilityGate says it is on screen:
//   - the player is on Home (this control's IsVisible),
//   - the launcher window isn't minimized or hidden,
//   - no launcher overlay covers Home (version picker, launch/update scene —
//     HomePage reports these; account panel / sign-in — MainWindow reports),
//   - other windows cover less than half of the banner: WindowOcclusion walks
//     the Win32 z-order every 1.5 s, and only while on Home and not minimized
//     (zero cost otherwise). A game on ANOTHER monitor doesn't cover it, so
//     the banner keeps running on a second screen while Minecraft plays,
//   - the Windows session is unlocked and awake.
// The feed itself is fetched regardless: it's configuration from Will's own
// site, never an ad, and fetching it shows nothing to anyone.
// ============================================================================
public partial class PromoBanner : UserControl
{
    private static readonly TimeSpan FeedRefreshInterval = TimeSpan.FromMinutes(30);
    private static readonly TimeSpan OcclusionPollInterval = TimeSpan.FromSeconds(1.5);
    private static readonly TimeSpan NetworkRetryDelay = TimeSpan.FromMinutes(10);
    private static readonly TimeSpan ImageTimeout = TimeSpan.FromSeconds(8);
    private const int MaxImageBytes = 512 * 1024;
    // Slide travel during rotation, matching the page-switch nudge in MainWindow.
    private const double SlideOffset = 10;

    private readonly BannerVisibilityGate _gate = new();
    private readonly PromoFeedLoader _loader = new(PromoHttp.GetTextAsync, OriginPaths.PromoFeedCache);
    private readonly DispatcherTimer _occlusionTimer;
    private readonly DispatcherTimer _rotateTimer;
    private readonly DispatcherTimer _discordTimer;
    private readonly DispatcherTimer _feedTimer;
    // Decoded promo images by URL; null = that URL failed (retried after the next feed refresh).
    private readonly Dictionary<Uri, ImageSource?> _images = new();

    private Window? _window;
    private IntPtr _hwnd;
    private bool _started;
    private bool _shownOnce;

    private PromoFeed? _feed;
    private string? _feedJson;
    private IReadOnlyList<HousePromo> _slides = Array.Empty<HousePromo>();
    private int _index = -1;
    private long _slideRevision;
    private long _iconRevision;

    private string? _discordCode;
    private DiscordServerInfo? _discordInfo;
    private bool _discordInvalid;
    private bool _discordFetching;
    private DateTime _discordFetchedAt = DateTime.MinValue;

    private BannerAdHost? _adHost;
    private DateTime _networkFailedAt = DateTime.MinValue;

    public PromoBanner()
    {
        InitializeComponent();

        _occlusionTimer = new DispatcherTimer(DispatcherPriority.Background) { Interval = OcclusionPollInterval };
        _occlusionTimer.Tick += (_, _) => CheckOcclusion();
        _rotateTimer = new DispatcherTimer(DispatcherPriority.Normal);
        _rotateTimer.Tick += (_, _) => RotateNext();
        _discordTimer = new DispatcherTimer(DispatcherPriority.Background);
        _discordTimer.Tick += (_, _) => _ = RefreshDiscordAsync();
        _feedTimer = new DispatcherTimer(DispatcherPriority.Background) { Interval = FeedRefreshInterval };
        _feedTimer.Tick += (_, _) => _ = RefreshFeedAsync();

        _gate.ActiveChanged += OnActiveChanged;
        IsVisibleChanged += (_, _) => UpdateGate(() => _gate.OnHomePage = IsVisible);
        Loaded += OnLoaded;
        Dispatcher.ShutdownStarted += OnShutdown;
    }

    // ---- Reports from the pages that own the overlays ----------------------

    /// <summary>HomePage: the version picker or the launch/update scene is up.</summary>
    public void SetHomeSceneOpen(bool open) => UpdateGate(() => _gate.HomeSceneOpen = open);

    /// <summary>MainWindow: the account panel or the sign-in card (and its scrim) is up.</summary>
    public void SetChromeOverlayOpen(bool open) => UpdateGate(() => _gate.ChromeOverlayOpen = open);

    // ---- Lifecycle -----------------------------------------------------------

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_window == null && Window.GetWindow(this) is { } window)
        {
            _window = window;
            _hwnd = new WindowInteropHelper(window).EnsureHandle();
            window.StateChanged += (_, _) => SyncWindowState();
            window.IsVisibleChanged += (_, _) => SyncWindowState();
            // Coming to the front is the moment occlusion most often changes;
            // answer right away instead of waiting for the next poll.
            window.Activated += (_, _) => { if (_occlusionTimer.IsEnabled) CheckOcclusion(); };
            SystemEvents.SessionSwitch += OnSessionSwitch;
            SystemEvents.PowerModeChanged += OnPowerModeChanged;
            SyncWindowState();
        }

        if (!_started)
        {
            _started = true;
            // After the first frame is on screen: the banner never competes
            // with the launcher's cold start.
            Dispatcher.InvokeAsync(() => _ = StartFeedAsync(), DispatcherPriority.ApplicationIdle);
        }
    }

    private void OnShutdown(object? sender, EventArgs e)
    {
        // SystemEvents handlers are static roots; drop them explicitly.
        SystemEvents.SessionSwitch -= OnSessionSwitch;
        SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        _occlusionTimer.Stop();
        _rotateTimer.Stop();
        _discordTimer.Stop();
        _feedTimer.Stop();
        _adHost?.Dispose();
        _adHost = null;
    }

    // ---- Visibility gate wiring ---------------------------------------------

    private void UpdateGate(Action change)
    {
        change();
        SyncOcclusionWatch();
    }

    private void SyncWindowState()
    {
        if (_window == null) return;
        UpdateGate(() => _gate.WindowMinimized =
            _window.WindowState == WindowState.Minimized || !_window.IsVisible);
    }

    // SystemEvents may raise on its own thread; hop to the UI thread.
    private void OnSessionSwitch(object sender, SessionSwitchEventArgs e)
    {
        bool? inactive = e.Reason switch
        {
            SessionSwitchReason.SessionLock or SessionSwitchReason.ConsoleDisconnect
                or SessionSwitchReason.RemoteDisconnect => true,
            SessionSwitchReason.SessionUnlock or SessionSwitchReason.ConsoleConnect
                or SessionSwitchReason.RemoteConnect => false,
            _ => null,
        };
        if (inactive is bool value)
            Dispatcher.InvokeAsync(() => UpdateGate(() => _gate.SessionInactive = value));
    }

    private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode is PowerModes.Suspend or PowerModes.Resume)
        {
            var suspended = e.Mode == PowerModes.Suspend;
            Dispatcher.InvokeAsync(() => UpdateGate(() => _gate.SessionInactive = suspended));
        }
    }

    private void SyncOcclusionWatch()
    {
        if (_gate.ShouldWatchOcclusion && _hwnd != IntPtr.Zero)
        {
            if (_occlusionTimer.IsEnabled) return;
            CheckOcclusion(); // answer now, not 1.5 s from now
            _occlusionTimer.Start();
        }
        else
        {
            _occlusionTimer.Stop();
        }
    }

    private void CheckOcclusion()
    {
        if (!_gate.ShouldWatchOcclusion) return;
        _gate.Occluded = IsBannerHidden();
    }

    private bool IsBannerHidden()
    {
        if (Root.Visibility != Visibility.Visible || ActualWidth < 1 || ActualHeight < 1
            || PresentationSource.FromVisual(this) == null)
            return true;

        // PointToScreen returns physical pixels (DPI and transforms applied),
        // the same space Win32 window rectangles use.
        var a = PointToScreen(new Point(0, 0));
        var b = PointToScreen(new Point(ActualWidth, ActualHeight));
        var rect = new ScreenRect(
            (int)Math.Floor(Math.Min(a.X, b.X)), (int)Math.Floor(Math.Min(a.Y, b.Y)),
            (int)Math.Ceiling(Math.Max(a.X, b.X)), (int)Math.Ceiling(Math.Max(a.Y, b.Y)));
        return WindowOcclusion.IsHidden(_hwnd, rect);
    }

    private void OnActiveChanged(bool active)
    {
        if (active)
        {
            RestartRotation();
            if (_discordCode != null && !_discordInvalid)
            {
                _discordTimer.Start();
                if (DateTime.UtcNow - _discordFetchedAt >= _discordTimer.Interval) _ = RefreshDiscordAsync();
            }
            // A failed ad network gets its retry the next time it could show.
            if (_adHost == null) SyncNetworkMode();
            _adHost?.SetActive(true);
        }
        else
        {
            _rotateTimer.Stop();
            _discordTimer.Stop();
            _adHost?.SetActive(false);
        }
    }

    // ---- Feed ------------------------------------------------------------------

    private async Task StartFeedAsync()
    {
        try
        {
            var local = await Task.Run(_loader.LoadLocal);
            ApplyFeed(local);
            await RefreshFeedAsync();
        }
        finally
        {
            _feedTimer.Start();
        }
    }

    private async Task RefreshFeedAsync()
    {
        var remote = await _loader.FetchRemoteAsync(CancellationToken.None);
        if (remote != null) ApplyFeed(remote);
    }

    private void ApplyFeed(PromoFeedResult result)
    {
        if (result.Json == _feedJson) return;
        _feedJson = result.Json;
        _feed = result.Feed;
        _images.Clear();

        _rotateTimer.Interval = _feed.RotateInterval;
        _discordTimer.Interval = _feed.Discord.RefreshInterval;

        if (_feed.Discord.InviteCode != _discordCode)
        {
            _discordCode = _feed.Discord.InviteCode;
            _discordInfo = null;
            _discordInvalid = false;
            _discordFetchedAt = DateTime.MinValue;
            _discordTimer.Stop();
            if (_gate.IsActive && _discordCode != null)
            {
                _discordTimer.Start();
                _ = RefreshDiscordAsync();
            }
        }

        SyncNetworkMode();
        RebuildSlides();
        if (_gate.IsActive) RestartRotation();
    }

    // ---- Ad-network mode ----------------------------------------------------

    private void SyncNetworkMode()
    {
        var network = _feed?.Network;
        var wanted = network is { IsUsable: true }
                     && DateTime.UtcNow - _networkFailedAt >= NetworkRetryDelay;

        if (wanted && _adHost == null)
        {
            _adHost = new BannerAdHost(NetworkHost, network!.Url!, network.RefreshInterval);
            _adHost.Failed += OnAdFailed;
            _adHost.SetActive(_gate.IsActive);
        }
        else if (wanted)
        {
            _adHost!.Configure(network!.Url!, network.RefreshInterval);
        }
        else if (_adHost != null)
        {
            _adHost.Dispose();
            _adHost = null;
        }

        var networkMode = _adHost != null;
        NetworkHost.Visibility = networkMode ? Visibility.Visible : Visibility.Collapsed;
        HouseCard.Visibility = networkMode ? Visibility.Collapsed : Visibility.Visible;
        Dots.Visibility = networkMode ? Visibility.Collapsed : Visibility.Visible;
        UpdateRootVisibility();
    }

    private void OnAdFailed(object? sender, EventArgs e)
    {
        if (!ReferenceEquals(sender, _adHost)) return;
        _adHost = null;
        _networkFailedAt = DateTime.UtcNow;
        SyncNetworkMode();
        RebuildSlides();
        if (_gate.IsActive) RestartRotation();
    }

    // ---- Discord ---------------------------------------------------------------

    private async Task RefreshDiscordAsync()
    {
        if (_discordCode is not { } code || _discordInvalid || _discordFetching || !_gate.IsActive) return;
        _discordFetching = true;
        try
        {
            var lookup = await DiscordInvite.LookupAsync(PromoHttp.GetTextAsync, code, CancellationToken.None);
            if (code != _discordCode) return; // the feed changed the invite meanwhile
            _discordFetchedAt = DateTime.UtcNow;

            switch (lookup.Status)
            {
                case DiscordLookupStatus.Ok:
                    _discordInfo = lookup.Info;
                    break;
                case DiscordLookupStatus.InvalidInvite:
                    _discordInfo = null;
                    _discordInvalid = true;
                    _discordTimer.Stop();
                    break;
                // Unavailable: keep the last good info, try next interval.
            }
            RebuildSlides();
        }
        finally
        {
            _discordFetching = false;
        }
    }

    // ---- House promo rotation ------------------------------------------------

    private void RebuildSlides()
    {
        var currentId = _index >= 0 && _index < _slides.Count ? _slides[_index].Id : null;
        _slides = _feed == null ? Array.Empty<HousePromo>() : PromoRotation.Playable(_feed, _discordInfo != null);
        _index = PromoRotation.IndexAfterChange(_slides, currentId);

        if (_index >= 0)
        {
            // Same promo still showing → refresh its text in place (e.g. new
            // Discord counts); a different one → swap without animation.
            Populate(_slides[_index]);
        }
        RenderDots();
        UpdateRootVisibility();
        if (_slides.Count < 2) _rotateTimer.Stop();
    }

    private void RestartRotation()
    {
        _rotateTimer.Stop();
        if (_gate.IsActive && _adHost == null && _slides.Count > 1) _rotateTimer.Start();
    }

    private void RotateNext()
    {
        // Hover holds the current card: never yank a promo out from under the
        // pointer. (Not keyboard focus — a click leaves focus on the card, and
        // rotation must not stay frozen after the player comes back.)
        if (!_gate.IsActive || _adHost != null || _slides.Count < 2 || IsMouseOver)
            return;

        _index = PromoRotation.Next(_index, _slides.Count);
        var promo = _slides[_index];
        RenderDots();

        var revision = ++_slideRevision;
        var exit = (Duration)FindResource("Motion.Exit");
        var enter = (Duration)FindResource("Motion.Scene");
        if (exit.TimeSpan == TimeSpan.Zero)
        {
            // Windows "show animations" off (App zeroes the Motion.* keys).
            Populate(promo);
            return;
        }

        var easeIn = new CubicEase { EasingMode = EasingMode.EaseIn };
        var easeOut = new CubicEase { EasingMode = EasingMode.EaseOut };
        var fadeOut = new DoubleAnimation(0, exit) { EasingFunction = easeIn };
        fadeOut.Completed += (_, _) =>
        {
            if (revision != _slideRevision) return;
            Populate(promo);
            SlideContent.BeginAnimation(OpacityProperty,
                new DoubleAnimation(0, 1, enter) { EasingFunction = easeOut });
            SlideTransform.BeginAnimation(TranslateTransform.XProperty,
                new DoubleAnimation(SlideOffset, 0, enter) { EasingFunction = easeOut });
        };
        SlideContent.BeginAnimation(OpacityProperty, fadeOut, HandoffBehavior.SnapshotAndReplace);
        SlideTransform.BeginAnimation(TranslateTransform.XProperty,
            new DoubleAnimation(-SlideOffset, exit) { EasingFunction = easeIn },
            HandoffBehavior.SnapshotAndReplace);
    }

    private void Populate(HousePromo promo)
    {
        if (promo.Kind == PromoKind.Discord && _discordInfo is { } info)
        {
            LabelText.Text = promo.Label;
            TitleText.Text = info.Name;
            SubtitleText.Text = DiscordInvite.DescribeCounts(info);
            SubtitleText.Visibility = Visibility.Visible;
            OnlineDot.Visibility = info.Online != null ? Visibility.Visible : Visibility.Collapsed;
            ExternalIcon.Visibility = Visibility.Visible;
            HouseCard.Cursor = System.Windows.Input.Cursors.Hand;
            HouseCard.ToolTip = "Join on Discord";
            AutomationProperties.SetName(HouseCard, $"Join {info.Name} on Discord");
            SetIcon(info.IconUrl);
            return;
        }

        LabelText.Text = promo.Label;
        TitleText.Text = promo.Title;
        SubtitleText.Text = promo.Subtitle;
        SubtitleText.Visibility = promo.Subtitle.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        OnlineDot.Visibility = Visibility.Collapsed;
        var clickable = promo.ClickUrl != null;
        ExternalIcon.Visibility = clickable ? Visibility.Visible : Visibility.Collapsed;
        HouseCard.Cursor = clickable ? System.Windows.Input.Cursors.Hand : System.Windows.Input.Cursors.Arrow;
        HouseCard.ToolTip = clickable ? promo.ClickUrl!.Host : null;
        AutomationProperties.SetName(HouseCard, promo.Title);
        SetIcon(promo.ImageUrl);
    }

    private void RenderDots()
    {
        Dots.Children.Clear();
        if (_slides.Count < 2) return;
        for (var i = 0; i < _slides.Count; i++)
        {
            Dots.Children.Add(new Rectangle
            {
                Width = i == _index ? 10 : 4,
                Height = 2,
                Margin = new Thickness(3, 0, 0, 0),
                Fill = (Brush)FindResource(i == _index ? "Brush.Accent" : "Brush.SoftStroke"),
            });
        }
    }

    private void UpdateRootVisibility()
    {
        var hasContent = _adHost != null || _slides.Count > 0;
        // Before the first feed is applied the row stays reserved (invisible);
        // after it, an empty feed collapses it.
        if (_feed != null && !hasContent)
        {
            Root.Visibility = Visibility.Collapsed;
            return;
        }
        Root.Visibility = Visibility.Visible;

        if (hasContent && !_shownOnce)
        {
            _shownOnce = true;
            Root.BeginAnimation(OpacityProperty,
                new DoubleAnimation(0, 1, (Duration)FindResource("Motion.Scene"))
                {
                    EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
                });
        }
    }

    private void HouseCard_Click(object sender, RoutedEventArgs e)
    {
        if (_index < 0 || _index >= _slides.Count) return;
        var promo = _slides[_index];
        if (promo.Kind == PromoKind.Discord)
        {
            if (_discordCode != null) ExternalLink.Open(DiscordInvite.JoinUrl(_discordCode));
            return;
        }
        ExternalLink.Open(promo.ClickUrl);
    }

    // ---- Images ----------------------------------------------------------------

    private async void SetIcon(Uri? url)
    {
        var revision = ++_iconRevision;
        if (url != null && _images.TryGetValue(url, out var cached))
        {
            ShowIcon(cached);
            return;
        }

        ShowIcon(null);
        if (url == null) return;

        var image = await LoadImageAsync(url);
        _images[url] = image;
        if (revision == _iconRevision) ShowIcon(image);
    }

    // No image (none given, still loading, or failed) → the Origin mark.
    private void ShowIcon(ImageSource? image)
    {
        IconImage.Source = image;
        IconMark.Visibility = image == null ? Visibility.Visible : Visibility.Collapsed;
    }

    private static async Task<ImageSource?> LoadImageAsync(Uri url)
    {
        var bytes = await PromoHttp.GetBytesAsync(url, MaxImageBytes, ImageTimeout, CancellationToken.None);
        if (bytes == null) return null;

        // Decode off the UI thread; a frozen bitmap is free to cross threads.
        return await Task.Run(() =>
        {
            try
            {
                var bitmap = new BitmapImage();
                bitmap.BeginInit();
                bitmap.CacheOption = BitmapCacheOption.OnLoad;
                bitmap.DecodePixelWidth = 96; // 44 px slot at up to 2x DPI
                bitmap.StreamSource = new MemoryStream(bytes);
                bitmap.EndInit();
                bitmap.Freeze();
                return (ImageSource?)bitmap;
            }
            catch (Exception ex) when (ex is NotSupportedException or FileFormatException
                                           or InvalidOperationException or ArgumentException)
            {
                return null;
            }
        });
    }
}
