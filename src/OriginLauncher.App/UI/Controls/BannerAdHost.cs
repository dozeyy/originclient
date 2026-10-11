using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Promos;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The banner's ad-network mode: the feed's "network.url" page (an Origin
/// page on the website that will carry the ad network's tag) hosted in a
/// dedicated WebView2. Created lazily — the first time the banner is
/// actually on screen in network mode — so house-promo launches never pay
/// for a browser process.
///
/// Rules this class enforces (PromoBanner's gate decides WHEN; this decides HOW):
///   - Loads and reloads only while active. Inactive = control collapsed
///     (WebView2 controller IsVisible = false, page sees
///     document.visibilityState "hidden") and the renderer suspended, which
///     also freezes the ad network's own in-page refresh timers.
///   - Reload cadence counts ON-SCREEN time only (feed "refreshSeconds",
///     floor 30 s): hidden time never brings a reload closer.
///   - Always muted; no devtools, context menu, zoom, accelerator keys,
///     autofill, script dialogs, downloads, or permission prompts.
///   - Popups and target=_blank links are swallowed and, if the player
///     clicked, opened in the default browser. The frame itself can never
///     leave the ad page's host (a click that tries is sent to the browser).
///   - Nothing about the player is sent: the URL carries no query, and the
///     profile lives in its own folder (OriginPaths.BannerWebViewData),
///     separate from the Microsoft sign-in WebView2.
/// Any failure (no WebView2 runtime, crash, page error) raises
/// <see cref="Failed"/> and the banner falls back to house promos.
/// </summary>
internal sealed class BannerAdHost : IDisposable
{
    // One environment per process for this user-data folder; WebView2
    // requires every control sharing a folder to share the same options.
    private static Task<CoreWebView2Environment>? s_environment;

    private static readonly TimeSpan RefreshCheckInterval = TimeSpan.FromSeconds(5);

    private readonly Border _host;
    private readonly DispatcherTimer _refreshTimer;
    private WebView2? _webView;
    private Uri _url;
    private TimeSpan _refreshInterval;
    private bool _active;
    private bool _initStarted;
    private bool _navigatePending;
    private bool _disposed;
    private ulong _ourNavigationId;
    private TimeSpan _shownSinceLoad;
    private DateTime? _shownSince;

    /// <summary>The ad page can't be shown (see class comment). Raised at most once.</summary>
    public event EventHandler? Failed;

    public BannerAdHost(Border host, Uri url, TimeSpan refreshInterval)
    {
        _host = host;
        _url = url;
        _refreshInterval = refreshInterval;
        _refreshTimer = new DispatcherTimer(DispatcherPriority.Background) { Interval = RefreshCheckInterval };
        _refreshTimer.Tick += (_, _) => ReloadIfDue();
    }

    /// <summary>The feed changed the page or cadence. A new page loads the next time the banner is on screen.</summary>
    public void Configure(Uri url, TimeSpan refreshInterval)
    {
        _refreshInterval = refreshInterval;
        if (Uri.Compare(url, _url, UriComponents.AbsoluteUri, UriFormat.SafeUnescaped, StringComparison.Ordinal) == 0)
            return;
        _url = url;
        _navigatePending = true;
        if (_active) NavigateIfPending();
    }

    public void SetActive(bool active)
    {
        if (_disposed || active == _active) return;
        _active = active;

        if (active)
        {
            _shownSince = DateTime.UtcNow;
            if (!_initStarted)
            {
                _initStarted = true;
                _navigatePending = true;
                _ = InitializeAsync();
                return;
            }
            if (_webView == null) return; // still initializing; it checks _active when done

            _webView.Visibility = Visibility.Visible;
            if (_webView.CoreWebView2 is { IsSuspended: true } core) core.Resume();
            NavigateIfPending();
            _refreshTimer.Start();
        }
        else
        {
            if (_shownSince is DateTime since) _shownSinceLoad += DateTime.UtcNow - since;
            _shownSince = null;
            _refreshTimer.Stop();
            if (_webView == null) return;

            _webView.Visibility = Visibility.Collapsed;
            _ = SuspendAsync(_webView);
        }
    }

    private async Task InitializeAsync()
    {
        try
        {
            s_environment ??= CoreWebView2Environment.CreateAsync(
                browserExecutableFolder: null,
                userDataFolder: OriginPaths.BannerWebViewData);
            var environment = await s_environment;
            if (_disposed) return;

            var webView = new WebView2
            {
                // Card color: the slot never flashes white while the page loads.
                DefaultBackgroundColor = System.Drawing.Color.FromArgb(255, 0x1D, 0x24, 0x20),
                Visibility = _active ? Visibility.Visible : Visibility.Collapsed,
            };
            _webView = webView;
            _host.Child = webView;

            await webView.EnsureCoreWebView2Async(environment);
            if (_disposed) return;
            ApplyLockdown(webView.CoreWebView2);

            if (_active)
            {
                NavigateIfPending();
                _refreshTimer.Start();
            }
            else
            {
                _ = SuspendAsync(webView);
            }
        }
        catch (Exception)
        {
            // Deliberately broad: missing/broken WebView2 runtime, locked or
            // unwritable profile folder, COM failures — whatever the cause,
            // the answer is the same (house promos), and the banner must never
            // surface a crash dialog. A failed environment isn't kept, so the
            // retry (PromoBanner, 10 minutes later) really starts over.
            if (s_environment is { IsCompleted: true, IsCompletedSuccessfully: false }) s_environment = null;
            Fail();
        }
    }

    private void ApplyLockdown(CoreWebView2 core)
    {
        var settings = core.Settings;
        settings.AreDevToolsEnabled = false;
        settings.AreDefaultContextMenusEnabled = false;
        settings.AreDefaultScriptDialogsEnabled = false;
        settings.AreBrowserAcceleratorKeysEnabled = false;
        settings.AreHostObjectsAllowed = false;
        settings.IsWebMessageEnabled = false;
        settings.IsZoomControlEnabled = false;
        settings.IsPinchZoomEnabled = false;
        settings.IsSwipeNavigationEnabled = false;
        settings.IsStatusBarEnabled = false;
        settings.IsPasswordAutosaveEnabled = false;
        settings.IsGeneralAutofillEnabled = false;
        settings.IsBuiltInErrorPageEnabled = false;

        core.IsMuted = true;
        // A page can't unmute itself, but re-assert if anything flips it.
        core.IsMutedChanged += (_, _) => { if (!core.IsMuted) core.IsMuted = true; };

        core.NewWindowRequested += (_, e) =>
        {
            e.Handled = true;
            if (e.IsUserInitiated && Uri.TryCreate(e.Uri, UriKind.Absolute, out var target))
                ExternalLink.Open(target);
        };

        core.NavigationStarting += (_, e) =>
        {
            if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out var target))
            {
                e.Cancel = true;
                return;
            }
            if (target.Scheme == "about") return;
            if (target.Scheme == Uri.UriSchemeHttps
                && string.Equals(target.IdnHost, _url.IdnHost, StringComparison.OrdinalIgnoreCase))
                return;

            e.Cancel = true;
            if (e.IsUserInitiated) ExternalLink.Open(target);
        };

        core.NavigationCompleted += (_, e) =>
        {
            if (e.NavigationId != _ourNavigationId) return;
            if (!e.IsSuccess || e.HttpStatusCode >= 400) Fail();
        };

        core.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
        core.DownloadStarting += (_, e) => e.Cancel = true;

        core.ProcessFailed += (_, e) =>
        {
            if (e.ProcessFailedKind is CoreWebView2ProcessFailedKind.BrowserProcessExited
                or CoreWebView2ProcessFailedKind.RenderProcessExited
                or CoreWebView2ProcessFailedKind.RenderProcessUnresponsive)
                Fail();
        };
    }

    // Navigation only ever starts here, and only while on screen.
    private void NavigateIfPending()
    {
        if (!_navigatePending || !_active || _webView?.CoreWebView2 is not { } core) return;
        _navigatePending = false;
        StartLoad(core);
    }

    private void ReloadIfDue()
    {
        if (!_active || _webView?.CoreWebView2 is not { } core) return;
        var shown = _shownSinceLoad + (_shownSince is DateTime since ? DateTime.UtcNow - since : TimeSpan.Zero);
        if (shown >= _refreshInterval) StartLoad(core);
    }

    private void StartLoad(CoreWebView2 core)
    {
        _shownSinceLoad = TimeSpan.Zero;
        _shownSince = DateTime.UtcNow;
        void Track(object? sender, CoreWebView2NavigationStartingEventArgs e)
        {
            core.NavigationStarting -= Track;
            _ourNavigationId = e.NavigationId;
        }
        core.NavigationStarting += Track;
        core.Navigate(_url.AbsoluteUri);
    }

    private static async Task SuspendAsync(WebView2 webView)
    {
        try
        {
            if (webView.CoreWebView2 is { IsSuspended: false } core)
                await core.TrySuspendAsync();
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.Runtime.InteropServices.COMException)
        {
            // Suspension is a best-effort saving; hidden is what matters.
        }
    }

    // Posted, not inline: Fail is often reached from inside a WebView2 event
    // callback, and disposing the control inside its own callback is unsafe.
    private void Fail()
    {
        if (_disposed) return;
        _host.Dispatcher.InvokeAsync(() =>
        {
            if (_disposed) return;
            Dispose();
            Failed?.Invoke(this, EventArgs.Empty);
        });
    }

    // Explicit, like MicrosoftSignInPanel: WebView2 doesn't reliably release
    // its browser process / profile lock just by leaving the visual tree.
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _active = false;
        _refreshTimer.Stop();
        if (_webView != null)
        {
            _host.Child = null;
            _webView.Dispose();
            _webView = null;
        }
    }
}
