using Avalonia;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Promos;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// "Add Microsoft account" (WPF UI/Controls/MicrosoftSignInPanel, Mac MicrosoftSignInPanel): the same Core flows as
// Windows and the Mac; the account is stored through AccountDrawer.SaveAsync (refresh token and session encrypted by
// Core's per-OS key store before they touch disk).
//
// Two ways in, the same two as Windows and the Mac:
//  1. In the card: Microsoft's page in a NativeWebView (WebKitGTK) with a throw-away data store. Core builds the
//     auth-code request (PKCE verifier + state); the redirect to oauth20_desktop.srf is caught as it starts (cancelled,
//     never loaded) and Core trades the code for the Microsoft -> Xbox -> Minecraft tokens. The redirect's state rules
//     (Core SignInRedirect, probed 2026-10-13): a different state is ignored, a code without state is refused (CSRF
//     guard), our state gives the code or the error that ends this sign-in.
//  2. With a code (Core MicrosoftAuthenticator.DeviceCode): "Go to microsoft.com/link and type ABCD-1234", from this PC
//     or a phone. The card switches to it by itself when Microsoft's page can't open here, on the Windows card's rules:
//     the web engine not up after 15 s (no libwebkit2gtk-4.1: it is only a recommended package and not in the AppImage),
//     the web view failing to build, or the FIRST page failing to load (DeviceCodeFlow.PageFailedToOpen). A slow page
//     on a working engine is waited for, never thrown away. "Sign in with a code instead" (the Mac card's addition)
//     switches by hand.
//
// Render runs (ShotRunner.Active) never create the web view and never touch the network: the shots call ShowLoading /
// ShowMessage / TourShowCode to show each state with made-up data.
public sealed class SignInPanel : Panel
{
    // How long the web engine may take to come up before the card switches to the code (WPF/Mac WebStartLimit).
    private static readonly TimeSpan WebStartLimit = TimeSpan.FromSeconds(15);

    private readonly MainWindow _window;
    private readonly CancellationTokenSource _cts = new();
    private readonly Border _well;
    private readonly Panel _pageHost = new();
    private readonly Border _loading;
    private readonly TextBlock _loadingText = Ui.Body("Loading sign-in...", Palette.TextDim);
    private readonly StackPanel _code;
    private readonly TextBlock _codeHint = Ui.Wrap(Ui.Body("", Palette.TextDim));
    private readonly TextBlock _userCode = Ui.Text("", TypeScale.Display, FontWeight.Bold, Palette.Text);
    private readonly TextBlock _copyLabel;
    private readonly StackPanel _message;
    private readonly TextBlock _messageTitle = Ui.Wrap(Ui.Title(""));
    private readonly TextBlock _messageText = Ui.Wrap(Ui.Body("", Palette.TextDim));
    private readonly TextBlock _headerTitle = Ui.Title("Sign in with Microsoft");
    private readonly Pressable _useCode;
    private readonly Grid _header;

    private string _verifier = "", _state = "", _verificationUri = "";
    private CancellationTokenSource? _webFlow; // the page's sign-in, cancelled when the card switches to the code
    private TaskCompletionSource<string>? _pageCode;
    private NativeWebView? _webView;
    private bool _adapterUp, _codeStarted, _holdOverlay, _done;

    /// <summary>Open the sign-in card over the window (the account drawer stays open behind it).</summary>
    public static SignInPanel Open(MainWindow window)
    {
        var panel = new SignInPanel(window);
        window.OpenDialog(panel, dismissable: false, onClosed: panel.Closed, width: Sizes.SignInCard);
        if (!Shots.ShotRunner.Active) panel.Start();
        return panel;
    }

    private SignInPanel(MainWindow window)
    {
        _window = window;
        // The dialog pads 24; the card wants a 16 inset (Size.SignInCard layout).
        Margin = new Thickness(-Space.S2);

        var close = Ui.IconButton(Icons.Close, "Cancel sign-in");
        close.Click += (_, _) => _window.CloseDialog();
        _header = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, 0, 0, Space.S3) };
        _header.Children.Add(_headerTitle.At(0).Align(HorizontalAlignment.Left));
        _header.Children.Add(close.At(1));

        // ---- A message (failed, already added, almost there): shown on the cover, like WPF ----
        var ok = Ui.Button("OK", Look.Primary);
        ok.MinWidth = 140;
        ok.HorizontalAlignment = HorizontalAlignment.Center;
        ok.Click += (_, _) => _window.CloseDialog();
        _messageTitle.TextAlignment = TextAlignment.Center;
        _messageText.TextAlignment = TextAlignment.Center;
        _message = Ui.Column(0, _messageTitle, _messageText.Margin(0, Space.S2, 0, Space.S6), ok);
        _message.MaxWidth = 380;
        _message.HorizontalAlignment = HorizontalAlignment.Center;
        _message.VerticalAlignment = VerticalAlignment.Center;
        _message.IsVisible = false;
        _loadingText.HorizontalAlignment = HorizontalAlignment.Center;
        _loadingText.VerticalAlignment = VerticalAlignment.Center;
        _loadingText.TextAlignment = TextAlignment.Center;
        _loadingText.TextWrapping = TextWrapping.Wrap;

        // ---- Sign in with a code (the WPF/Mac CodePanel: title, hint, the code on a well, Copy + Open, the wait line) ----
        var codeTitle = Ui.Title("Enter this code");
        codeTitle.TextAlignment = TextAlignment.Center;
        codeTitle.HorizontalAlignment = HorizontalAlignment.Center;
        _codeHint.TextAlignment = TextAlignment.Center;
        _userCode.HorizontalAlignment = HorizontalAlignment.Center;
        var codeWell = new Border
        {
            Background = Palette.Bg, CornerRadius = new CornerRadius(Radius.Md), Padding = new Thickness(20, 12),
            HorizontalAlignment = HorizontalAlignment.Center, Child = _userCode,
        };
        var copy = Ui.Button("Copy code", Look.Secondary, Icons.Copy, Sizes.ControlMd);
        copy.Tip("Copy the code");
        _copyLabel = ((StackPanel)copy.Child!).Children.OfType<TextBlock>().First();
        copy.Click += async (_, _) =>
        {
            if (string.IsNullOrEmpty(_userCode.Text) || TopLevel.GetTopLevel(this)?.Clipboard is not { } clipboard) return;
            try
            {
                await clipboard.SetTextAsync(_userCode.Text);
                _copyLabel.Text = "Copied";
            }
            catch (Exception) { /* no clipboard owner (a bare X session): the code is still on screen to type */ }
        };
        var openPage = Ui.Button("Open Microsoft's page", Look.Primary, Icons.External, Sizes.ControlMd);
        openPage.Click += (_, _) => ExternalLink.Open(
            Uri.TryCreate(_verificationUri, UriKind.Absolute, out var uri) && uri.Scheme == Uri.UriSchemeHttps ? uri : null);
        var codeButtons = Ui.Row(Space.S2, copy, openPage);
        codeButtons.HorizontalAlignment = HorizontalAlignment.Center;
        var waiting = Ui.Wrap(Ui.Caption("Waiting for you to finish on Microsoft's page..."));
        waiting.TextAlignment = TextAlignment.Center;
        _code = Ui.Column(0, codeTitle.Margin(0, 0, 0, Space.S2), _codeHint.Margin(0, 0, 0, Space.S4),
            codeWell.Margin(0, 0, 0, 20), codeButtons.Margin(0, 0, 0, Space.S4), waiting);
        _code.MaxWidth = 400;
        _code.HorizontalAlignment = HorizontalAlignment.Center;
        _code.VerticalAlignment = VerticalAlignment.Center;
        _code.IsVisible = false;

        // The cover over the page while it loads, finishes, shows the code or reports. The well has no height of its own
        // until Microsoft's page shows (ShowPage), so the card fits what it says instead of standing 564 tall around it.
        _loading = new Border
        {
            Background = Palette.Bg, CornerRadius = new CornerRadius(Radius.Md), Padding = new Thickness(Space.S6),
            Child = new Panel { Children = { _loadingText, _code, _message } },
        };
        _well = new Border
        {
            CornerRadius = new CornerRadius(Radius.Md), ClipToBounds = true,
            Child = new Panel { Children = { _pageHost, _loading } },
        };

        _useCode = Ui.Button("Sign in with a code instead", Look.Ghost, height: Sizes.ControlMd);
        _useCode.HorizontalAlignment = HorizontalAlignment.Center;
        _useCode.Margin = new Thickness(0, Space.S2, 0, 0);
        _useCode.Click += (_, _) => UseCode(auto: false);

        Children.Add(Ui.Column(0, _header, _well, _useCode));
    }

    // ---- Real runs ----

    private void Start()
    {
        AccountDrawer.Open?.SetBusy(true);
        try
        {
            var view = new NativeWebView();
            view.EnvironmentRequested += (_, e) =>
            {
                // A fresh, throw-away profile per sign-in (WPF uses an InPrivate profile): no Microsoft cookies
                // linger, so "Add" always asks which account instead of silently re-using the last one.
                if (e is Avalonia.Platform.GtkWebViewEnvironmentRequestedEventArgs gtk) gtk.EphemeralDataManager = true;
            };
            view.AdapterCreated += (_, _) => _adapterUp = true;
            view.NavigationStarted += (_, e) =>
            {
                if (e.Request is { } uri && HandleRedirect(uri.ToString())) e.Cancel = true;
            };
            view.NavigationCompleted += (_, e) =>
            {
                // The redirect the card cancelled also "completes"; and once it is finishing or showing an outcome the
                // cover stays up.
                var ours = e.Request?.ToString().StartsWith(MicrosoftAuthenticator.TestRedirectUri, StringComparison.Ordinal) == true;
                if (_holdOverlay || ours) return;
                if (DeviceCodeFlow.PageFailedToOpen(e.IsSuccess, _pageShown, cancelledByCard: false)) UseCode(auto: true);
                else if (e.IsSuccess) ShowPage(true);
            };
            _webView = view;
            _pageHost.Children.Add(view);
        }
        catch (Exception)
        {
            UseCode(auto: true);
            return;
        }
        _ = RunPageAsync();
        // A web engine that never starts leaves the card loading: after WebStartLimit the code takes over by itself.
        DispatcherTimer.RunOnce(() => { if (!_adapterUp) UseCode(auto: true); }, WebStartLimit);
    }

    // The page's sign-in: navigate, wait (5 minutes, like WPF) for the redirect's code, trade it.
    private async Task RunPageAsync()
    {
        var auth = new MicrosoftAuthenticator();
        (var authUrl, _verifier, _state) = auth.BuildTestAuthorizationRequest();
        _webFlow = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
        _pageCode = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var ct = _webFlow.Token;
        await RunAsync(async () =>
        {
            _webView!.Navigate(new Uri(authUrl));
            var code = await _pageCode.Task.WaitAsync(TimeSpan.FromMinutes(5), ct);
            Finishing();
            return await auth.CompleteTestSignInAsync(code, _verifier, ct);
        }, ct);
    }

    /// <summary>The redirect from the web view. True = it was ours (stop that navigation).</summary>
    private bool HandleRedirect(string address)
    {
        switch (SignInRedirect.Classify(address, MicrosoftAuthenticator.TestRedirectUri, _state, out var code, out var error))
        {
            case RedirectOutcome.NotOurs:
                return false;
            case RedirectOutcome.Ignore:
                return true;
            case RedirectOutcome.Code:
                _pageCode?.TrySetResult(code);
                return true;
            default:
                _webFlow?.Cancel();
                Fail(error == "access_denied" ? "Sign-in was cancelled on Microsoft's page." : "Microsoft sign-in didn't finish. Please try again.");
                return true;
        }
    }

    // Every way in ends here (the page or the code): one place turns a result or a failure into what the player reads,
    // in the Windows card's sentences. A cancel (closed card, or the page's flow stopped because the card switched to
    // the code) is not a failure.
    private async Task RunAsync(Func<Task<AuthResult>> flow, CancellationToken ct)
    {
        try
        {
            var result = await flow();
            if (_done) return;
            var outcome = await AccountDrawer.SaveAsync(result);
            _done = true;
            if (outcome == null)
            {
                _window.CloseDialog();
                _window.CloseDrawer();
            }
            else ShowMessage(outcome.Title, outcome.Text);
        }
        catch (Exception) when (_done || ct.IsCancellationRequested) { /* closed, or moved on to the code */ }
        catch (TimeoutException) { Fail("Sign-in timed out - no response after 5 minutes."); }
        catch (MicrosoftAuthException ex) { Fail(ex.Message); }
        catch (HttpRequestException) { Fail("Couldn't reach Microsoft. Check your internet connection and try again."); }
        catch (Exception) { Fail("Sign-in didn't finish. Please try again."); }
    }

    // ---- Sign in with a code ----

    // Microsoft's page can't open in this card (auto), or the player asked: stop the page's flow and sign in with a code
    // (the device code flow, the same Xbox / Minecraft chain, so the account is stored exactly as from the page).
    private void UseCode(bool auto)
    {
        if (_done || _codeStarted || (_holdOverlay && auto)) return;
        _codeStarted = true;
        _webFlow?.Cancel();
        _holdOverlay = true;
        StopWebView();
        _headerTitle.Text = "Sign in with a code";
        _useCode.IsVisible = false;
        _loadingText.Text = "Asking Microsoft for a code...";
        _loadingText.IsVisible = true;
        ShowPage(false);
        var ct = _cts.Token;
        _ = RunAsync(async () =>
        {
            var authenticator = new MicrosoftAuthenticator();
            var start = await authenticator.StartDeviceCodeAsync(ct);
            ShowCode(start.UserCode, start.VerificationUri, auto);
            var result = await authenticator.CompleteDeviceCodeSignInAsync(start, ct);
            Finishing();
            return result;
        }, ct);
    }

    private void ShowCode(string userCode, string verificationUri, bool pageFailed)
    {
        _verificationUri = verificationUri;
        _userCode.Text = userCode;
        var where = $"Go to {DeviceCodeFlow.ShortAddress(verificationUri)} on this PC or your phone and type:";
        _codeHint.Text = pageFailed ? "Microsoft's sign-in page can't open in Origin on this PC. " + where : where;
        _loadingText.IsVisible = false;
        _message.IsVisible = false;
        _code.IsVisible = true;
    }

    // The code (or the page) is done; cover the wait while Xbox and Minecraft finish (a few seconds).
    private void Finishing()
    {
        _holdOverlay = true;
        _code.IsVisible = false;
        _useCode.IsVisible = false;
        ShowLoading("Finishing sign-in...");
    }

    private void Fail(string message)
    {
        if (_done) return;
        _done = true;
        AccountDrawer.Open?.ShowNote(message, error: true);
        ShowMessage("Couldn't add this account", message);
    }

    private void Closed()
    {
        _done = true;
        _cts.Cancel();
        StopWebView();
        AccountDrawer.Open?.SetBusy(false);
    }

    private void StopWebView()
    {
        if (_webView is { } view)
        {
            try { view.Stop(); } catch (Exception) { /* already gone */ }
        }
        _webView = null;
        _pageHost.Children.Clear();
    }

    /// <summary>
    /// Microsoft's page on show (true): the well takes the page's height and the cover lifts. Anything else
    /// (loading, finishing, the code, an outcome): the cover is up and the well fits it (WPF ShowPage). Once the page
    /// has shown, the web view is hidden under the cover: it is a native child window that would draw over the text.
    /// </summary>
    private void ShowPage(bool page)
    {
        if (page) _pageShown = true;
        _well.Height = page ? Sizes.SignInPage : double.NaN;
        _loading.IsVisible = !page;
        if (_pageShown && _webView != null) _webView.IsVisible = page;
    }

    private bool _pageShown;

    // ---- States (also what the render run shows) ----

    /// <summary>The page well with its loading line over it ("Loading sign-in..." / "Finishing sign-in...").</summary>
    public void ShowLoading(string text = "Loading sign-in...")
    {
        _loadingText.Text = text;
        _loadingText.IsVisible = true;
        _message.IsVisible = false;
        _code.IsVisible = false;
        ShowPage(false);
        _useCode.IsVisible = !_holdOverlay && text == "Loading sign-in...";
        _header.IsVisible = true;
    }

    /// <summary>The end of a sign-in that didn't simply work: a title, the reason, and OK.</summary>
    public void ShowMessage(string title, string text)
    {
        _holdOverlay = true;
        _messageTitle.Text = title;
        _messageText.Text = text;
        _loadingText.IsVisible = false;
        _code.IsVisible = false;
        _message.IsVisible = true;
        ShowPage(false);
        _useCode.IsVisible = false;
        StopWebView();
    }

    /// <summary>Render runs: the code view with a made-up code (no request is made), as when the page couldn't open.</summary>
    public void TourShowCode(string userCode, string verificationUri)
    {
        _holdOverlay = true;
        _headerTitle.Text = "Sign in with a code";
        _useCode.IsVisible = false;
        ShowPage(false);
        ShowCode(userCode, verificationUri, pageFailed: true);
    }
}
