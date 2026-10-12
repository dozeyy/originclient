using System.IO;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using Microsoft.Win32;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Promos;

namespace OriginLauncher.App.UI.Controls;

// ============================================================================
// HOME BANNER — the card under the Play card.
//
// Content: the remote promo feed (Core/Promos/PromoFeedLoader: live site →
// raw GitHub → on-disk cache → built-in), refreshed at startup and every 30
// minutes. Two modes:
//   - House promos: Will's own cards, a live Discord card (server name,
//     icon, online/member counts) and the tips slot (Core/Promos/PromoTips,
//     the next tip each visit), rotating every "rotateSeconds". Each card
//     shows wide preview art behind its text — the feed's previewUrl, else
//     the launcher's bundled art (Assets/Promos), Discord's own icon blurred
//     — zooming slowly while it's up. A progress bar along the bottom fills
//     over the rotation time; hovering the card pauses it, and clicking a
//     segment jumps to that card. Clicking the card itself opens its link
//     (website, Discord); a card without one (the tips) moves on to the next
//     card instead (Core/Promos/BannerCard.ClickFor). Card text is never cut
//     off: it wraps, and shrinks a step at a time until it fits (FitText).
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
    // How far the preview art zooms in over one card's time on screen.
    private const double PreviewZoomTo = 1.07;
    private const double SegmentWidth = 18;
    // The text column's width cap: past it the text would run over the
    // brightest part of the preview art (BannerCard's last text step lifts it).
    private const double TextMaxWidth = 250;

    private readonly BannerVisibilityGate _gate = new();
    private readonly PromoFeedLoader _loader = new(PromoHttp.GetTextAsync, OriginPaths.PromoFeedCache);
    private readonly DispatcherTimer _occlusionTimer;
    private readonly DispatcherTimer _discordTimer;
    private readonly DispatcherTimer _feedTimer;
    // Decoded promo images by URL; null = that URL failed (retried after the next feed refresh).
    private readonly Dictionary<Uri, ImageSource?> _images = new();
    private readonly Dictionary<Uri, ImageSource?> _previews = new();
    // Bundled preview art by name; loaded once, kept for the session.
    private readonly Dictionary<string, ImageSource?> _bundled = new();

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
    private long _previewRevision;
    // Rotation clock: the current segment's fill animation. Its Completed moves
    // to the next card, so pausing it (hover) pauses the rotation too.
    private Storyboard? _progress;
    private Rectangle? _activeFill;
    // Which tip the tips slot shows; advanced each time the slot comes round.
    private int _tipTurn = -1;
    // CI screen render only (TourShowCard): the card on screen is fixed.
    private bool _tourPinned;

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
        _discordTimer = new DispatcherTimer(DispatcherPriority.Background);
        _discordTimer.Tick += (_, _) => _ = RefreshDiscordAsync();
        _feedTimer = new DispatcherTimer(DispatcherPriority.Background) { Interval = FeedRefreshInterval };
        _feedTimer.Tick += (_, _) => _ = RefreshFeedAsync();

        _gate.ActiveChanged += OnActiveChanged;
        IsVisibleChanged += (_, _) => UpdateGate(() => _gate.OnHomePage = IsVisible);
        Loaded += OnLoaded;
        Layers.SizeChanged += (_, _) => ClipLayers();
        TextStack.MaxWidth = TextMaxWidth;
        SlideContent.SizeChanged += (_, _) => FitText();
        // Hover holds the current card: never yank a promo out from under the
        // pointer. (Not keyboard focus — a click leaves focus on the card, and
        // rotation must not stay frozen after the player comes back.)
        Root.MouseEnter += (_, _) => _progress?.Pause(this);
        Root.MouseLeave += (_, _) => _progress?.Resume(this);
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
        StopProgress();
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
            StopProgress();
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
        if (result.Json == _feedJson || _tourPinned) return;
        _feedJson = result.Json;
        _feed = result.Feed;
        _images.Clear();
        _previews.Clear();

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
        if (_tourPinned) return;
        var currentId = _index >= 0 && _index < _slides.Count ? _slides[_index].Id : null;
        _slides = _feed == null ? Array.Empty<HousePromo>() : PromoRotation.Playable(_feed, _discordInfo != null);
        _index = PromoRotation.IndexAfterChange(_slides, currentId);

        if (_index >= 0)
        {
            // Same promo still showing → refresh its text in place (e.g. new
            // Discord counts); a different one → swap without animation.
            if (_slides[_index].Kind == PromoKind.Tips && _tipTurn < 0) _tipTurn = 0;
            Populate(_slides[_index]);
        }
        UpdateRootVisibility();
        // The segments are rebuilt, so the clock restarts on the new ones.
        RestartRotation();
    }

    private void RestartRotation()
    {
        StopProgress();
        RenderDots();
        if (_gate.IsActive && _adHost == null && _slides.Count > 1 && _feed != null) StartProgress(_feed.RotateInterval);
    }

    private void StartProgress(TimeSpan duration)
    {
        if (_activeFill == null) return;
        var fill = new DoubleAnimation(0, 1, duration);
        Storyboard.SetTarget(fill, _activeFill);
        Storyboard.SetTargetProperty(fill, new PropertyPath("RenderTransform.ScaleX"));
        var clock = new Storyboard();
        clock.Children.Add(fill);
        clock.Completed += (_, _) =>
        {
            if (ReferenceEquals(clock, _progress)) RotateNext();
        };
        _progress = clock;
        clock.Begin(this, isControllable: true);
        if (Root.IsMouseOver) clock.Pause(this);
    }

    private void StopProgress()
    {
        var clock = _progress;
        _progress = null;
        clock?.Stop(this);
    }

    private void RotateNext()
    {
        if (!_gate.IsActive || _adHost != null || _slides.Count < 2 || _tourPinned)
            return;
        ShowSlide(PromoRotation.Next(_index, _slides.Count));
    }

    // Moves to slide `index` with the slide-out/slide-in, and restarts the clock.
    private void ShowSlide(int index)
    {
        _index = index;
        var promo = _slides[_index];
        if (promo.Kind == PromoKind.Tips) _tipTurn++;
        RestartRotation();

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
        PreviewImage.BeginAnimation(OpacityProperty,
            new DoubleAnimation(0, exit) { EasingFunction = easeIn }, HandoffBehavior.SnapshotAndReplace);
    }

    private void Populate(HousePromo promo)
    {
        PopulateContent(promo);
        FitText();
    }

    private void PopulateContent(HousePromo promo)
    {
        var click = BannerCard.ClickFor(promo, _slides.Count);
        // Anything a click does shows the hand; the whole card is the button.
        HouseCard.Cursor = click == CardClick.None ? System.Windows.Input.Cursors.Arrow : System.Windows.Input.Cursors.Hand;

        if (promo.Kind == PromoKind.Discord && _discordInfo is { } info)
        {
            LabelText.Text = promo.Label;
            TitleText.Text = info.Name;
            SubtitleText.Text = DiscordInvite.DescribeCounts(info);
            SubtitleText.Visibility = Visibility.Visible;
            OnlineDot.Visibility = info.Online != null ? Visibility.Visible : Visibility.Collapsed;
            ExternalIcon.Visibility = Visibility.Visible;
            HouseCard.ToolTip = "Join on Discord";
            AutomationProperties.SetName(HouseCard, $"Join {info.Name} on Discord");
            SetIcon(info.IconUrl);
            // The server's own icon, blown up and blurred, is the Discord card's art.
            SetPreview(info.IconUrl, null, blurred: true);
            return;
        }

        if (promo.Kind == PromoKind.Tips)
        {
            var tip = PromoTips.At(Math.Max(_tipTurn, 0));
            LabelText.Text = promo.Label;
            TitleText.Text = tip.Title;
            SubtitleText.Text = tip.Subtitle;
            SubtitleText.Visibility = Visibility.Visible;
            OnlineDot.Visibility = Visibility.Collapsed;
            ExternalIcon.Visibility = Visibility.Collapsed;
            HouseCard.ToolTip = "Next tip";
            AutomationProperties.SetName(HouseCard, $"Tip: {tip.Title}. Next tip");
            SetIcon(null);
            SetPreview(null, tip.Preview, blurred: false);
            return;
        }

        LabelText.Text = promo.Label;
        TitleText.Text = promo.Title;
        SubtitleText.Text = promo.Subtitle;
        SubtitleText.Visibility = promo.Subtitle.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        OnlineDot.Visibility = Visibility.Collapsed;
        var opensLink = click == CardClick.OpenLink;
        ExternalIcon.Visibility = opensLink ? Visibility.Visible : Visibility.Collapsed;
        HouseCard.ToolTip = opensLink ? promo.ClickUrl?.Host : click == CardClick.Advance ? "Next" : null;
        AutomationProperties.SetName(HouseCard, promo.Title);
        SetIcon(promo.ImageUrl);
        SetPreview(promo.PreviewUrl, PromoTips.BundledPreviewFor(promo.Id), blurred: false);
    }

    // Sizes the card's text to fit its box (SlideContent, which stays clear
    // of the progress bar): the largest BannerCard.TextSteps step at which the
    // wrapped text's full height fits. Runs whenever the text, the icon or
    // the box changes; nothing is ever trimmed with "…".
    private void FitText()
    {
        var box = new Size(SlideContent.ActualWidth, SlideContent.ActualHeight);
        if (box.Width < 1 || box.Height < 1) return; // not laid out yet: SizeChanged calls back

        // The text column gets what the icon and the link arrow leave.
        var width = box.Width;
        if (IconBox.Visibility == Visibility.Visible)
            width -= IconBox.Width + IconBox.Margin.Left + IconBox.Margin.Right;
        if (ExternalIcon.Visibility == Visibility.Visible)
            width -= ExternalIcon.Width + ExternalIcon.Margin.Left + ExternalIcon.Margin.Right;
        var constraint = new Size(Math.Max(width, 0), double.PositiveInfinity);

        var step = BannerCard.ChooseTextStep(candidate =>
        {
            ApplyTextStep(candidate);
            TextStack.Measure(constraint);
            return TextStack.DesiredSize.Height <= box.Height;
        });
        ApplyTextStep(step);
    }

    private void ApplyTextStep(BannerTextStep step)
    {
        TitleText.FontSize = step.TitleSize;
        TitleText.LineHeight = step.TitleLineHeight;
        SubtitleText.FontSize = step.SubtitleSize;
        SubtitleText.LineHeight = step.SubtitleLineHeight;
        TextStack.MaxWidth = step.Wide ? double.PositiveInfinity : TextMaxWidth;
    }

    // One segment per slide; the current one holds a fill that the rotation
    // clock scales from 0 to 1. Segments are click targets (taller than drawn).
    private void RenderDots()
    {
        Dots.Children.Clear();
        _activeFill = null;
        if (_slides.Count < 2 || _adHost != null) return;
        for (var i = 0; i < _slides.Count; i++)
        {
            var track = new Grid { Width = SegmentWidth, Height = 2, VerticalAlignment = VerticalAlignment.Bottom };
            track.Children.Add(new Rectangle
            {
                Fill = (Brush)FindResource(i < _index ? "Brush.TextDim" : "Brush.SoftStroke"),
            });
            if (i == _index)
            {
                _activeFill = new Rectangle
                {
                    Fill = (Brush)FindResource("Brush.Accent"),
                    RenderTransform = new ScaleTransform(_gate.IsActive ? 0 : 1, 1),
                };
                track.Children.Add(_activeFill);
            }
            var target = i;
            var hit = new Border
            {
                Background = Brushes.Transparent,
                Padding = new Thickness(0, 8, 0, 3),
                Margin = new Thickness(0, 0, 4, 0),
                Cursor = System.Windows.Input.Cursors.Hand,
                Child = track,
                ToolTip = SlideName(_slides[i]),
            };
            AutomationProperties.SetName(hit, $"Show {SlideName(_slides[i])}");
            hit.MouseLeftButtonUp += (_, e) =>
            {
                e.Handled = true;
                if (target != _index && target < _slides.Count) ShowSlide(target);
            };
            Dots.Children.Add(hit);
        }
    }

    private string SlideName(HousePromo promo) => promo.Kind switch
    {
        PromoKind.Discord => _discordInfo?.Name ?? "Discord",
        PromoKind.Tips => "Tips",
        _ => promo.Title,
    };

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
        switch (BannerCard.ClickFor(promo, _slides.Count))
        {
            case CardClick.OpenLink when promo.Kind == PromoKind.Discord:
                if (_discordCode != null) ExternalLink.Open(DiscordInvite.JoinUrl(_discordCode));
                break;
            case CardClick.OpenLink:
                ExternalLink.Open(promo.ClickUrl);
                break;
            case CardClick.Advance:
                // Same slide animation as the rotation; ShowSlide restarts the clock.
                ShowSlide(PromoRotation.Next(_index, _slides.Count));
                break;
        }
    }

    /// <summary>
    /// CI screen render only (UI/RenderShots): shows <paramref name="slides"/>
    /// with card <paramref name="index"/> up (a tips card on tip
    /// <paramref name="tipTurn"/>), fitted and drawn at rest, and pins it —
    /// no rotation, feed refresh or animation moves it afterwards.
    /// </summary>
    public void TourShowCard(IReadOnlyList<HousePromo> slides, int index, int tipTurn)
    {
        _tourPinned = true;
        StopProgress();
        _slideRevision++;
        SlideContent.BeginAnimation(OpacityProperty, null);
        SlideContent.Opacity = 1;
        SlideTransform.BeginAnimation(TranslateTransform.XProperty, null);
        SlideTransform.X = 0;
        Root.BeginAnimation(OpacityProperty, null);
        Root.Opacity = 1;
        Root.Visibility = Visibility.Visible;
        HouseCard.Visibility = Visibility.Visible;
        Dots.Visibility = Visibility.Visible;
        NetworkHost.Visibility = Visibility.Collapsed;

        _slides = slides;
        _index = index;
        _tipTurn = tipTurn;
        Populate(slides[index]);
        RenderDots();
    }

    // The card's rounded rect (inside its 1 px border), so zoomed art stays inside.
    private void ClipLayers()
    {
        var r = ((CornerRadius)FindResource("Radius.Card")).TopLeft;
        Layers.Clip = new RectangleGeometry(new Rect(Layers.RenderSize), r, r);
    }

    // ---- Images ----------------------------------------------------------------

    // The card's wide art: a remote previewUrl first, else the bundled art by
    // name, else none (the faint Origin mark shows). Fades in, then zooms
    // slowly for as long as the card is up.
    private async void SetPreview(Uri? url, string? bundled, bool blurred)
    {
        var revision = ++_previewRevision;
        ImageSource? image = null;
        if (url != null)
        {
            if (!_previews.TryGetValue(url, out image))
            {
                ShowPreview(null, blurred);
                image = await LoadImageAsync(url, blurred ? 96 : 800);
                _previews[url] = image;
                if (revision != _previewRevision) return;
            }
        }
        if (image == null && bundled != null) image = BundledPreview(bundled);
        ShowPreview(image, blurred);
    }

    private void ShowPreview(ImageSource? image, bool blurred)
    {
        PreviewImage.Source = image;
        PreviewImage.Effect = blurred && image != null ? new BlurEffect { Radius = 28 } : null;
        AmbientMark.Visibility = image == null ? Visibility.Visible : Visibility.Collapsed;

        var target = image == null ? 0 : blurred ? 0.45 : 1;
        var enter = (Duration)FindResource("Motion.Scene");
        if (enter.TimeSpan == TimeSpan.Zero || image == null)
        {
            PreviewImage.BeginAnimation(OpacityProperty, null);
            PreviewImage.Opacity = target;
            PreviewZoom.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            PreviewZoom.BeginAnimation(ScaleTransform.ScaleYProperty, null);
            PreviewZoom.ScaleX = PreviewZoom.ScaleY = 1;
            return;
        }
        PreviewImage.BeginAnimation(OpacityProperty, new DoubleAnimation(target, TimeSpan.FromMilliseconds(420))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
        });
        // Ken Burns: a slow, linear push-in over the card's time on screen.
        var span = (_feed?.RotateInterval ?? PromoFeedParser.DefaultRotate) + TimeSpan.FromSeconds(1);
        var zoom = new DoubleAnimation(1, PreviewZoomTo, span);
        PreviewZoom.BeginAnimation(ScaleTransform.ScaleXProperty, zoom);
        PreviewZoom.BeginAnimation(ScaleTransform.ScaleYProperty, zoom);
    }

    private ImageSource? BundledPreview(string name)
    {
        if (_bundled.TryGetValue(name, out var cached)) return cached;
        ImageSource? image = null;
        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.UriSource = new Uri($"pack://application:,,,/Assets/Promos/{name}.jpg", UriKind.Absolute);
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.EndInit();
            bitmap.Freeze();
            image = bitmap;
        }
        catch (Exception ex) when (ex is IOException or NotSupportedException or InvalidOperationException
                                       or ArgumentException)
        {
            image = null;   // a missing resource just means no art
        }
        _bundled[name] = image;
        return image;
    }

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

    // The small square only shows when there's an icon to put in it.
    private void ShowIcon(ImageSource? image)
    {
        IconImage.Source = image;
        var visibility = image == null ? Visibility.Collapsed : Visibility.Visible;
        if (IconBox.Visibility == visibility) return;
        IconBox.Visibility = visibility;
        FitText(); // the icon takes width from the text
    }

    private static async Task<ImageSource?> LoadImageAsync(Uri url, int decodeWidth = 96)
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
                bitmap.DecodePixelWidth = decodeWidth; // icon: 44 px slot at up to 2x DPI; preview: the card
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
