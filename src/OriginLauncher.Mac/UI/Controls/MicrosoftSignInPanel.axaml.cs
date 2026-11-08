using Avalonia;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Layout;
using Avalonia.Markup.Xaml;
using Avalonia.Platform;
using Avalonia.Threading;
using OriginLauncher.App.Core.Auth;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// Twin of the WPF UI/Controls/MicrosoftSignInPanel.xaml.cs. MicrosoftAuthenticator owns the whole MSA -> Xbox ->
/// XSTS -> Minecraft chain; this card only shows Microsoft's page (a WKWebView through Avalonia's NativeWebView) and
/// catches its final redirect, or runs "sign in with a code" (DeviceCodeFlow). Either way the result goes out through
/// <see cref="SignInSucceeded"/> to MainWindow, which stores it only through AccountSwitcherPanel.CompleteSignIn
/// (MinecraftSessionBroker.AddSignedIn).
///
/// Secrets: the redirect address carries the authorization code, so it is never logged or shown; the device code
/// stays in memory; the user code is shown on screen and nowhere else. Each card's web view uses a non-persistent
/// data store (the Mac twin of WebView2's InPrivate profile): no Microsoft cookie survives to sign a second account
/// in as the first.
/// </summary>
public sealed partial class MicrosoftSignInPanel : UserControl, IDisposable
{
    public event EventHandler<AuthResult>? SignInSucceeded;
    public event EventHandler<string>? SignInFailed;
    public event EventHandler? Cancelled;

    // How long the web view may take to come up before the code is offered on its own.
    private static readonly TimeSpan WebStartLimit = TimeSpan.FromSeconds(15);

    private readonly CancellationTokenSource _cts = new();
    private CancellationTokenSource? _webFlow;
    private readonly bool _pictureOnly;
    private NativeWebView? _web;
    private readonly TaskCompletionSource _adapter = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private TaskCompletionSource<string>? _codeSource;
    private string _state = "";
    private bool _holdOverlay; // the cover shows "Finishing sign-in...", the code or an outcome
    private bool _pageShown;
    private bool _codeStarted;
    private bool _disposed;
    private string _verificationUri = "";

    public MicrosoftSignInPanel() : this(pictureOnly: false) { }

    /// <param name="pictureOnly">A render-shots run: the card only, no web view, nothing goes online.</param>
    public MicrosoftSignInPanel(bool pictureOnly)
    {
        _pictureOnly = pictureOnly;
        InitializeComponent();
        CloseButton.Click += (_, _) => Cancel();
        OkButton.Click += (_, _) => Cancel();
        UseCodeButton.Click += (_, _) => UseCode();
        OpenPageButton.Click += (_, _) => OpenVerificationPage();
        CopyCodeButton.Click += async (_, _) =>
        {
            if (TopLevel.GetTopLevel(this)?.Clipboard is not { } clipboard || string.IsNullOrEmpty(UserCodeText.Text)) return;
            await clipboard.SetTextAsync(UserCodeText.Text);
            CopyCodeLabel.Text = "Copied";
        };
        AttachedToVisualTree += (_, _) => Start();
    }

    private bool _started;

    private void Start()
    {
        if (_started || _pictureOnly) return;
        _started = true;
        try
        {
            _web = new NativeWebView
            {
                // A pixel in the corner until Microsoft's page has drawn (ShowPage): the web view is a native view that
                // would sit over the cover's text, yet it must be on screen for WebKit to start.
                Width = 1, Height = 1,
                HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
            };
            _web.EnvironmentRequested += (_, e) =>
            {
                if (e is AppleWKWebViewEnvironmentRequestedEventArgs apple)
                {
                    apple.NonPersistentDataStore = true;
                    NonPersistentRequested = true;
                }
            };
            _web.AdapterCreated += (_, _) => _adapter.TrySetResult();
            _web.NavigationStarted += OnNavigationStarted;
            _web.NavigationCompleted += OnNavigationCompleted;
            _web.WebMessageReceived += OnWebMessage;
            WebHost.Content = _web;
        }
        catch (Exception)
        {
            OfferCode();
            return;
        }
        _ = StartWebAsync();
    }

    private async Task StartWebAsync()
    {
        if (await Task.WhenAny(_adapter.Task, Task.Delay(WebStartLimit)) != _adapter.Task)
        {
            OfferCode();
            return;
        }
        if (_codeStarted || _disposed) return;
        _webFlow = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
        await RunAsync(MicrosoftAuthenticator.IsTestMode ? SignInViaWebAsync : SignInViaLoopbackAsync, _webFlow.Token);
    }

    // Every way in ends here: one place turns a result or a failure into what the player reads (the same sentences as
    // the Windows card). A cancel (closed card, or switched to the code) is not a failure.
    private async Task RunAsync(Func<CancellationToken, Task<AuthResult>> flow, CancellationToken ct)
    {
        try
        {
            var result = await flow(ct);
            if (!_disposed) SignInSucceeded?.Invoke(this, result);
        }
        catch (OperationCanceledException)
        {
        }
        catch (TimeoutException)
        {
            Fail("Sign-in timed out - no response after 5 minutes.");
        }
        catch (MicrosoftAuthException ex)
        {
            // Always a plain sentence; raw HTTP detail never reaches the screen.
            Fail(ex.Message);
        }
        catch (System.Net.Http.HttpRequestException)
        {
            Fail("Couldn't reach Microsoft. Check your internet connection and try again.");
        }
        catch (Exception)
        {
            Fail("Sign-in didn't finish. Please try again.");
        }
    }

    // A card already closed (or showing an outcome) has nothing left to report.
    private void Fail(string message)
    {
        if (!_disposed && !_cts.IsCancellationRequested) SignInFailed?.Invoke(this, message);
    }

    // ---- Microsoft's page ----

    // The test client's redirect (login.live.com/oauth20_desktop.srf) is never a page to load: the web view raises
    // NavigationStarted for it and the navigation is cancelled before anything is fetched.
    private async Task<AuthResult> SignInViaWebAsync(CancellationToken ct)
    {
        var authenticator = new MicrosoftAuthenticator();
        var (authUrl, codeVerifier, state) = authenticator.BuildTestAuthorizationRequest();
        _state = state;
        ProbeAuthUrl = authUrl;
        _codeSource = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        _web!.Navigate(new Uri(authUrl));
        var authCode = await _codeSource.Task.WaitAsync(TimeSpan.FromMinutes(5), ct);

        // The page is done; cover its blank redirect while Xbox and Minecraft finish (a few seconds).
        Finishing();
        return await authenticator.CompleteTestSignInAsync(authCode, codeVerifier, ct);
    }

    // The production client (loopback redirect, MicrosoftAuthenticator.SignInAsync): the card only shows the page.
    private Task<AuthResult> SignInViaLoopbackAsync(CancellationToken ct) =>
        new MicrosoftAuthenticator().SignInAsync(url => Dispatcher.UIThread.Post(() => _web?.Navigate(new Uri(url))), ct);

    private void OnNavigationStarted(object? sender, WebViewNavigationStartingEventArgs e)
    {
        var uri = e.Request?.AbsoluteUri ?? "";
        // StartsWith, never Contains: the sign-in page's own address carries the redirect as its redirect_uri.
        if (!uri.StartsWith(MicrosoftAuthenticator.TestRedirectUri, StringComparison.Ordinal)) return;
        e.Cancel = true;
        var outcome = SignInRedirect.Classify(uri, MicrosoftAuthenticator.TestRedirectUri, _state, out var code, out var error);
        ProbeRedirect?.Invoke(outcome);
        switch (outcome)
        {
            case RedirectOutcome.Code:
                _codeSource?.TrySetResult(code);
                break;
            case RedirectOutcome.Error:
                _codeSource?.TrySetException(new MicrosoftAuthException("login", "Microsoft sign-in didn't finish. Please try again.",
                    $"no authorization code ({error})"));
                break;
        }
    }

    private void OnNavigationCompleted(object? sender, WebViewNavigationCompletedEventArgs e)
    {
        if (e.Request?.AbsoluteUri is { } uri && uri.StartsWith(MicrosoftAuthenticator.TestRedirectUri, StringComparison.Ordinal))
        {
            RedirectPageLoads++;
            return;
        }
        if (_holdOverlay) return;
        if (e.IsSuccess)
        {
            ShowPage(true);
            HookPageEscape();
        }
        else if (!_pageShown) OfferCode();
    }

    // Esc inside Microsoft's page closes the card, as it does everywhere else in the launcher: the page is a native view
    // that keeps the key to itself, so a listener in the page hands it back through the web view's message bridge
    // (Avalonia's invokeCSharpAction). Added once per page load; the message carries no page data.
    private const string EscapeMessage = "origin:escape";

    private const string EscapeScript =
        "if(!window.__originEsc){window.__originEsc=true;document.addEventListener('keydown',function(e){" +
        "if(e.key==='Escape'&&typeof invokeCSharpAction==='function'){invokeCSharpAction('" + EscapeMessage + "');}},true);}";

    private void HookPageEscape()
    {
        if (_web == null) return;
        try
        {
            _ = _web.InvokeScript(EscapeScript).ContinueWith(t => _ = t.Exception, TaskContinuationOptions.OnlyOnFaulted);
        }
        catch (Exception)
        {
            // No script bridge on this page (it never fails the sign-in): Esc then stays the page's own key.
        }
    }

    private void OnWebMessage(object? sender, WebMessageReceivedEventArgs e)
    {
        if (e.Body?.Trim('"') == EscapeMessage) Dispatcher.UIThread.Post(Cancel);
    }

    /// <summary>The sign-in probe: what the page's Esc listener sends, delivered as the page would.</summary>
    internal void ProbePageEscape() => OnWebMessage(this, new WebMessageReceivedEventArgs { Body = EscapeMessage });

    // ---- Sign in with a code ----

    // The page can't open here: say so, and make the code the main way in.
    private void OfferCode()
    {
        if (_disposed || _codeStarted || _holdOverlay) return;
        LoadingText.Text = "Microsoft's sign-in page couldn't open here. You can sign in with a code instead.";
        ShowPage(false);
        UseCodeButton.Theme = Res.Theme("Button.Primary.Medium");
        UseCodeButton.Margin = new Thickness(16, 0, 16, 16);
        PasskeyHint.IsVisible = false; // the code is the way in now, passkey or not
        CodeOffered = true;
    }

    private void UseCode()
    {
        if (_disposed || _codeStarted) return;
        _codeStarted = true;
        _webFlow?.Cancel();
        _holdOverlay = true;
        HeaderTitle.Text = "Sign in with a code";
        UseCodeButton.IsVisible = false;
        LoadingText.Text = "Asking Microsoft for a code...";
        ShowPage(false);
        ProbeCodeTask = RunAsync(CodeFlowAsync, _cts.Token);
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
        var shown = DeviceCodeFlow.ShortAddress(verificationUri);
        CodeHint.Text = $"Go to {shown} on this Mac or any other device and type:";
        LoadingText.IsVisible = false;
        CodePanel.IsVisible = true;
    }

    private void OpenVerificationPage()
    {
        if (!Uri.TryCreate(_verificationUri, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps) return;
        _ = TopLevel.GetTopLevel(this)?.Launcher.LaunchUriAsync(uri);
    }

    // ---- The cover ----

    private void Finishing()
    {
        _holdOverlay = true;
        CodePanel.IsVisible = false;
        LoadingText.IsVisible = true;
        LoadingText.Text = "Finishing sign-in...";
        UseCodeButton.IsVisible = false;
        ShowPage(false);
    }

    /// <summary>Replace the page with an outcome the player must read (a failed or deferred sign-in). The card stays
    /// open until they press OK or close it, which raises <see cref="Cancelled"/>.</summary>
    public void ShowMessage(string title, string text)
    {
        if (_disposed) return;
        _cts.Cancel();
        _holdOverlay = true;
        MessageTitle.Text = title;
        MessageText.Text = text;
        LoadingText.IsVisible = false;
        CodePanel.IsVisible = false;
        UseCodeButton.IsVisible = false;
        MessagePanel.IsVisible = true;
        ShowPage(false);
    }

    // Microsoft's page on show: the well takes the page's height (Size.SignInPage) and the cover lifts. Anything else
    // (loading, the code, finishing, an outcome): the cover is up and the well fits it. Once the page has shown, the web
    // view is hidden under the cover (a native view would draw over the cover's text).
    private void ShowPage(bool page)
    {
        if (page) _pageShown = true;
        PageArea.Height = page ? Res.D("Size.SignInPage") : double.NaN;
        LoadingOverlay.IsVisible = !page;
        if (_web == null) return;
        if (page)
        {
            _web.Width = double.NaN;
            _web.Height = double.NaN;
            _web.HorizontalAlignment = HorizontalAlignment.Stretch;
            _web.VerticalAlignment = VerticalAlignment.Stretch;
        }
        if (_pageShown) _web.IsVisible = page;
    }

    private void Cancel()
    {
        _cts.Cancel();
        Cancelled?.Invoke(this, EventArgs.Empty);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _cts.Cancel();
        _webFlow?.Dispose();
        // The web view goes with the card: its non-persistent store (and every cookie in it) dies with it.
        WebHost.Content = null;
        _web = null;
    }

    // ---- Render shots and the CI sign-in probe (SignInProbe.cs). None of these sign anyone in. ----

    /// <summary>Render shots: the code view with a made-up code (no request is made).</summary>
    internal void TourShowCode(string userCode, string verificationUri)
    {
        _holdOverlay = true;
        HeaderTitle.Text = "Sign in with a code";
        UseCodeButton.IsVisible = false;
        ShowPage(false);
        ShowCode(userCode, verificationUri);
    }

    internal NativeWebView? ProbeWeb => _web;
    internal Task ProbeAdapter => _adapter.Task;
    internal bool NonPersistentRequested { get; private set; }
    internal bool CodeOffered { get; private set; }
    internal string ProbeAuthUrl { get; private set; } = "";
    internal string ProbeState => _state;
    internal int RedirectPageLoads { get; private set; }
    internal bool ProbePageShown => _pageShown;
    internal event Action<RedirectOutcome>? ProbeRedirect;
    internal Task? ProbeCodeTask { get; private set; }
    internal string ProbeUserCode => UserCodeText.Text ?? "";
    internal string ProbeVerificationUri => _verificationUri;
    internal bool ProbeCodeShown => CodePanel.IsVisible;
    internal void ProbeUseCode() => UseCode();
    internal void ProbeCancel() => Cancel();
}
