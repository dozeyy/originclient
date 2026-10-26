using System.Windows;
using System.Windows.Controls;
using Microsoft.Web.WebView2.Core;
using OriginLauncher.App.Core.Auth;

namespace OriginLauncher.App.UI.Controls;

// Hosts the Microsoft OAuth login page inside the launcher via WebView2,
// instead of the old approach of shelling out to the system's default
// browser. MicrosoftAuthenticator still owns the whole MSA -> Xbox Live ->
// XSTS -> Minecraft chain and the loopback redirect listener; this control
// only supplies "how to show the user the authorization URL".
public partial class MicrosoftSignInPanel : UserControl, IDisposable
{
    public event EventHandler<AuthResult>? SignInSucceeded;
    public event EventHandler<string>? SignInFailed;
    public event EventHandler? Cancelled;

    private readonly CancellationTokenSource _cts = new();
    private bool _disposed;
    private bool _holdOverlay; // the overlay now shows "Finishing sign-in..." or an outcome

    public MicrosoftSignInPanel()
    {
        InitializeComponent();
        // The page's own background while it loads: the field colour, from the theme tokens.
        var field = (System.Windows.Media.Color)FindResource("Color.BgAlt");
        WebView.DefaultBackgroundColor = System.Drawing.Color.FromArgb(field.A, field.R, field.G, field.B);

        // Every sign-in runs in a throwaway InPrivate profile. The panel used
        // to run in WebView2's default profile (<exe>.WebView2, on disk, shared
        // by every panel), which kept the first account's "stay signed in"
        // Microsoft cookies (MSPAuth / WLSSC / __Host-MSAAUTHP on login.live.com).
        // Microsoft then silently issued a code for THAT account on every later
        // "Add Microsoft Account", so a second account could never be added -
        // the store just refreshed account #1. InPrivate keeps nothing between
        // panels. The working folder is under our data root (OriginPaths); App
        // deletes the old default folder at startup.
        // Must be set before the CoreWebView2 is created (EnsureCoreWebView2Async).
        WebView.CreationProperties = new Microsoft.Web.WebView2.Wpf.CoreWebView2CreationProperties
        {
            UserDataFolder = OriginLauncher.App.Core.OriginPaths.SignInWebViewData,
            IsInPrivateModeEnabled = true,
            // InPrivate webviews with the same profile name share in-memory
            // cookies inside one browser process, so a panel that hasn't fully
            // torn down could still hand its session to the next one. A fresh
            // name per panel gives each sign-in its own empty cookie jar.
            ProfileName = "signin-" + Guid.NewGuid().ToString("N")
        };

        Loaded += MicrosoftSignInPanel_Loaded;
    }

    private async void MicrosoftSignInPanel_Loaded(object sender, RoutedEventArgs e)
    {
        // A render-shots run (scratch data root) pictures the card only: no browser process, no Microsoft page.
        if (OriginLauncher.App.Core.OriginPaths.IsScratchRoot) return;
        try
        {
            await WebView.EnsureCoreWebView2Async();
            WebView.CoreWebView2.NavigationCompleted += (_, _) =>
            {
                // The cancelled redirect also "completes": keep covering the
                // page once it's finishing or showing an outcome.
                if (!_holdOverlay) ShowPage(true);
            };

            var result = MicrosoftAuthenticator.IsTestMode
                ? await SignInViaPrismTestAsync(_cts.Token)
                : await new MicrosoftAuthenticator().SignInAsync(url => WebView.Source = new Uri(url), _cts.Token);

            SignInSucceeded?.Invoke(this, result);
        }
        catch (OperationCanceledException)
        {
            // User closed the panel — not a failure, nothing to show.
        }
        catch (TimeoutException)
        {
            SignInFailed?.Invoke(this, "Sign-in timed out — no response after 5 minutes.");
        }
        catch (MicrosoftAuthException ex)
        {
            // Always a plain sentence (RateLimited = the friendly "Microsoft is
            // limiting sign-ins" line); raw HTTP detail never reaches the screen.
            SignInFailed?.Invoke(this, ex.Message);
        }
        catch (System.Net.Http.HttpRequestException)
        {
            SignInFailed?.Invoke(this, "Couldn't reach Microsoft. Check your internet connection and try again.");
        }
        catch (Exception)
        {
            SignInFailed?.Invoke(this, "Sign-in didn't finish. Please try again.");
        }
    }

    /// <summary>
    /// Replace the Microsoft page with an outcome the player must read (a
    /// failed or deferred sign-in). The card stays open until they press OK
    /// or close it, which raises <see cref="Cancelled"/>.
    /// </summary>
    public void ShowMessage(string title, string text)
    {
        if (_disposed) return;
        _cts.Cancel();
        _holdOverlay = true;
        MessageTitle.Text = title;
        MessageText.Text = text;
        LoadingText.Visibility = Visibility.Collapsed;
        MessagePanel.Visibility = Visibility.Visible;
        ShowPage(false);
    }

    /// <summary>
    /// Microsoft's page on show (true): the well takes the page's height (Size.SignInPage) and the cover lifts.
    /// Anything else (loading, finishing, an outcome): the cover is up and the well fits it, so the card is only
    /// as tall as what it says. Once the page has shown, the browser is hidden under a cover: it is a separate
    /// window that would draw over the cover's text in the smaller well.
    /// </summary>
    private void ShowPage(bool page)
    {
        if (page) _pageShown = true;
        PageArea.Height = page ? (double)FindResource("Size.SignInPage") : double.NaN;
        LoadingOverlay.Visibility = page ? Visibility.Collapsed : Visibility.Visible;
        if (_pageShown) WebView.Visibility = page ? Visibility.Visible : Visibility.Hidden;
    }

    private bool _pageShown;

    // TEMPORARY — testing only. Prism's client ID expects a custom URI scheme
    // redirect (prismlauncher://oauth/microsoft) rather than a localhost
    // loopback. Since sign-in happens inside our own WebView2, we don't need
    // an OS-registered protocol handler for it — WebView2 fires
    // NavigationStarting for that URI regardless of whether Windows knows
    // about the scheme, so we just intercept it there and cancel before it
    // ever tries to actually navigate anywhere.
    private async Task<AuthResult> SignInViaPrismTestAsync(CancellationToken ct)
    {
        var authenticator = new MicrosoftAuthenticator();
        var (authUrl, codeVerifier, expectedState) = authenticator.BuildTestAuthorizationRequest();

        var codeSource = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);

        void OnNavigationStarting(object? s, CoreWebView2NavigationStartingEventArgs args)
        {
            if (!args.Uri.StartsWith(MicrosoftAuthenticator.TestRedirectUri, StringComparison.Ordinal)) return;
            args.Cancel = true;

            // Only the answer to THIS request counts (login.live.com echoes
            // state on every redirect, success or error - probed 2026-10-13):
            //  - state present but different: a stale or forged redirect; ignore
            //    it so it can neither finish nor abort this sign-in;
            //  - a code without our state: refuse it (CSRF guard);
            //  - an error with no state: ignore (can't be ours);
            //  - our state: the code, or the error that ends this sign-in.
            var query = ParseQuery(new Uri(args.Uri).Query);
            var hasState = query.TryGetValue("state", out var returnedState);
            if (hasState && returnedState != expectedState) return;
            var hasCode = query.TryGetValue("code", out var code);
            if (!hasState)
            {
                if (hasCode)
                    codeSource.TrySetException(new MicrosoftAuthException("login",
                        "Microsoft sign-in didn't finish. Please try again.", "authorization code without state"));
                return;
            }
            if (hasCode)
                codeSource.TrySetResult(code!);
            else
                codeSource.TrySetException(new MicrosoftAuthException("login",
                    "Microsoft sign-in didn't finish. Please try again.",
                    $"no authorization code ({(query.TryGetValue("error", out var err) ? err : "unknown error")})"));
        }

        WebView.CoreWebView2.NavigationStarting += OnNavigationStarting;
        string authCode;
        try
        {
            WebView.Source = new Uri(authUrl);
            authCode = await codeSource.Task.WaitAsync(TimeSpan.FromMinutes(5), ct);
        }
        finally
        {
            WebView.CoreWebView2.NavigationStarting -= OnNavigationStarting;
        }

        // The Microsoft page is done; cover its blank redirect while Xbox and
        // Minecraft finish (a few seconds) so the card never looks stuck.
        _holdOverlay = true;
        LoadingText.Text = "Finishing sign-in...";
        ShowPage(false);
        return await authenticator.CompleteTestSignInAsync(authCode, codeVerifier, ct);
    }

    private static Dictionary<string, string> ParseQuery(string query)
    {
        var result = new Dictionary<string, string>();
        foreach (var pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var idx = pair.IndexOf('=');
            if (idx < 0) continue;
            result[Uri.UnescapeDataString(pair[..idx])] = Uri.UnescapeDataString(pair[(idx + 1)..]);
        }
        return result;
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        _cts.Cancel();
        Cancelled?.Invoke(this, EventArgs.Empty);
    }

    // MainWindow creates a brand-new MicrosoftSignInPanel (and its embedded
    // WebView2) every time the user clicks "Add Microsoft Account". WebView2
    // does not reliably release its browser process / user data folder lock
    // just because it's removed from the visual tree (Content = null) — the
    // WPF control's cleanup on Unload is not synchronous, so a second panel
    // created before the first one is GC'd can find the environment still
    // held and fail to load. Disposing explicitly the moment the panel closes
    // is what Microsoft's own WebView2 WPF samples do for exactly this
    // reason: it's what makes repeated sign-in attempts (a 2nd, 3rd account)
    // reliable instead of intermittently silently failing.
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        _cts.Cancel();
        _cts.Dispose();
        // This panel's one-off InPrivate profile: mark it for deletion so its
        // folder (EBWebView\WV2Profile_signin-<guid>) and Local State entry go
        // when the browser process exits. App sweeps any that survive.
        try
        {
            WebView.CoreWebView2?.Profile.Delete();
        }
        catch (Exception)
        {
            // Never block closing the card; the startup sweep catches leftovers.
        }
        WebView.Dispose();
    }
}
