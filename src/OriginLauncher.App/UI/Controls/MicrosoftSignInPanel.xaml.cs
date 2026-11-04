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

    // How long the browser engine may take to come up before the card switches to the code on its own.
    private static readonly TimeSpan WebStartLimit = TimeSpan.FromSeconds(15);

    private CancellationTokenSource? _webFlow; // the page's sign-in, cancelled when the card switches to the code
    private bool _started;
    private bool _codeStarted;
    private string _verificationUri = "";

    private async void MicrosoftSignInPanel_Loaded(object sender, RoutedEventArgs e)
    {
        // A render-shots run (scratch data root) pictures the card only: no browser process, no Microsoft page.
        if (OriginLauncher.App.Core.OriginPaths.IsScratchRoot || _started) return;
        _started = true;
        // Read once, up front: Cancel / Esc / a click outside can dispose this panel (and its token source) while the
        // browser is still starting, and touching _cts.Token after that throws.
        var ct = _cts.Token;
        try
        {
            await WebView.EnsureCoreWebView2Async().WaitAsync(WebStartLimit, ct);
        }
        catch (Exception) when (_disposed || ct.IsCancellationRequested)
        {
            return; // closed while the browser started: a Cancel, nothing to show
        }
        catch (Exception)
        {
            // No Edge WebView2 Runtime (Windows 10 LTSC / N, debloated installs), a broken one, or one that never came
            // up: Microsoft's page can't open here, so sign in with a code instead (the Mac card's fallback). Without
            // this the card could only ever say "Sign-in didn't finish" and the player could never add an account.
            UseCode();
            return;
        }
        if (_disposed || _codeStarted) return;
        WebView.CoreWebView2.NavigationCompleted += (_, args) =>
        {
            // The cancelled redirect also "completes": keep covering the page once it's finishing or showing an outcome.
            if (_holdOverlay) return;
            if (DeviceCodeFlow.PageFailedToOpen(args.IsSuccess, _pageShown,
                    args.WebErrorStatus == CoreWebView2WebErrorStatus.OperationCanceled))
                UseCode();
            else
                ShowPage(true);
        };

        _webFlow = CancellationTokenSource.CreateLinkedTokenSource(ct);
        Func<CancellationToken, Task<AuthResult>> pageFlow = MicrosoftAuthenticator.IsTestMode
            ? SignInViaPrismTestAsync
            : flowCt => new MicrosoftAuthenticator().SignInAsync(url => WebView.Source = new Uri(url), flowCt);
        await RunAsync(pageFlow, _webFlow.Token);
    }

    // Every way in ends here (the page or the code): one place turns a result or a failure into what the player reads.
    // A cancel (closed card, or the page's flow stopped because the card switched to the code) is not a failure.
    private async Task RunAsync(Func<CancellationToken, Task<AuthResult>> flow, CancellationToken ct)
    {
        try
        {
            var result = await flow(ct);
            if (!_disposed) SignInSucceeded?.Invoke(this, result);
        }
        catch (Exception) when (_disposed || ct.IsCancellationRequested)
        {
            // The player closed the card mid-start (a disposed WebView, a cancelled token), or the card moved on to the
            // code: never a "Sign-in didn't finish" error in the Accounts drawer.
        }
        catch (TimeoutException)
        {
            SignInFailed?.Invoke(this, "Sign-in timed out - no response after 5 minutes.");
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

    // ---- Sign in with a code ----

    // Microsoft's page can't open in this card: stop its flow and sign in with a code (the device code flow, the same
    // Xbox / Minecraft chain, so the account is stored exactly as from the page).
    private void UseCode()
    {
        if (_disposed || _codeStarted) return;
        _codeStarted = true;
        _webFlow?.Cancel();
        _holdOverlay = true;
        HeaderTitle.Text = "Sign in with a code";
        LoadingText.Text = "Asking Microsoft for a code...";
        // The browser is a separate window that would draw over the cover's text; it has nothing to show now.
        WebView.Visibility = Visibility.Collapsed;
        ShowPage(false);
        _ = RunAsync(CodeFlowAsync, _cts.Token);
    }

    private async Task<AuthResult> CodeFlowAsync(CancellationToken ct)
    {
        var authenticator = new MicrosoftAuthenticator();
        var start = await authenticator.StartDeviceCodeAsync(ct);
        ShowCode(start.UserCode, start.VerificationUri);
        var result = await authenticator.CompleteDeviceCodeSignInAsync(start, ct);
        Finishing();
        return result;
    }

    private void ShowCode(string userCode, string verificationUri)
    {
        _verificationUri = verificationUri;
        UserCodeText.Text = userCode;
        CodeHint.Text = "Microsoft's sign-in page can't open in Origin on this PC. " +
                        $"Go to {DeviceCodeFlow.ShortAddress(verificationUri)} on this PC or your phone and type:";
        LoadingText.Visibility = Visibility.Collapsed;
        CodePanel.Visibility = Visibility.Visible;
    }

    private void CopyCodeButton_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(UserCodeText.Text)) return;
        try
        {
            Clipboard.SetText(UserCodeText.Text);
            CopyCodeLabel.Text = "Copied";
        }
        catch (System.Runtime.InteropServices.COMException)
        {
            // Another app holds the clipboard: the code is still on screen to type.
        }
    }

    private void OpenPageButton_Click(object sender, RoutedEventArgs e) =>
        OriginLauncher.App.Core.Promos.ExternalLink.Open(
            Uri.TryCreate(_verificationUri, UriKind.Absolute, out var uri) && uri.Scheme == Uri.UriSchemeHttps ? uri : null);

    // The code (or the page) is done; cover the wait while Xbox and Minecraft finish (a few seconds).
    private void Finishing()
    {
        _holdOverlay = true;
        CodePanel.Visibility = Visibility.Collapsed;
        LoadingText.Visibility = Visibility.Visible;
        LoadingText.Text = "Finishing sign-in...";
        ShowPage(false);
    }

    /// <summary>Render shots: the code view with a made-up code (no request is made).</summary>
    internal void TourShowCode(string userCode, string verificationUri)
    {
        _holdOverlay = true;
        HeaderTitle.Text = "Sign in with a code";
        WebView.Visibility = Visibility.Collapsed;
        ShowPage(false);
        ShowCode(userCode, verificationUri);
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
        CodePanel.Visibility = Visibility.Collapsed;
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
        Finishing();
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
        _webFlow?.Dispose();
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
