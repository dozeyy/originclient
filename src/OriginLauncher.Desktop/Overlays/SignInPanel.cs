using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Promos;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// "Add Microsoft account" (WPF UI/Controls/MicrosoftSignInPanel): the same Core flow as Windows. Core builds the
// auth-code request (PKCE verifier + state), Microsoft's page redirects to oauth20_desktop.srf with ?code&state,
// and Core trades the code for the Microsoft -> Xbox -> Minecraft tokens; the account is stored through
// AccountDrawer.Save (refresh token and session encrypted by Core's per-OS key store before they touch disk).
//
// Two ways to reach that redirect, both always on offer:
//  1. In the card: Microsoft's page in a NativeWebView (WebKitGTK on Linux, WKWebView on the Mac) with a
//     throw-away data store, and the redirect caught as it starts (cancelled, never loaded).
//  2. In the browser: the system browser opens the same page (ExternalLink -> xdg-open / open); Microsoft ends on
//     a blank page whose address holds the code, and the player pastes that address here. Used when the web view
//     can't start (no libwebkit2gtk, a sandbox) or the player prefers their own browser.
// The same state rules apply to both (WPF probed them 2026-10-13): a different state is ignored or refused, a code
// without state is refused (CSRF guard), our state gives the code or the error that ends this sign-in.
//
// Render runs (ShotRunner.Active) never create the web view and never touch the network: the shots call
// ShowLoading / ShowMessage / ShowBrowser to show each state with made-up data.
public sealed class SignInPanel : Panel
{
    private readonly MainWindow _window;
    private readonly CancellationTokenSource _cts = new();
    private readonly Border _well;
    private readonly Panel _pageHost = new();
    private readonly Border _loading;
    private readonly TextBlock _loadingText = Ui.Body("Loading sign-in...", Palette.TextDim);
    private readonly StackPanel _browser;
    private readonly TextBox _paste;
    private readonly TextBlock _pasteError = Ui.Wrap(Ui.Caption("", Palette.DangerText));
    private readonly Pressable _finish;
    private readonly StackPanel _message;
    private readonly TextBlock _messageTitle = Ui.Wrap(Ui.Title(""));
    private readonly TextBlock _messageText = Ui.Wrap(Ui.Body("", Palette.TextDim));
    private string? _errorFor;  // the pasted text the shown error is about (typing anything else clears it)
    private readonly Pressable _browserSwitch;
    private readonly Grid _header;

    private MicrosoftAuthenticator? _auth;
    private string _authUrl = "", _verifier = "", _state = "";
    private Control? _webView;
    private bool _finishing, _done;

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
        _header.Children.Add(Ui.Title("Sign in with Microsoft").At(0).Align(HorizontalAlignment.Left));
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

        // The cover over the page while it loads, finishes or reports. The well has no height of its own until
        // Microsoft's page shows (ShowPage), so the card fits one sentence instead of standing 564 tall around it.
        _loading = new Border
        {
            Background = Palette.Bg, CornerRadius = new CornerRadius(Radius.Md), Padding = new Thickness(Space.S6),
            Child = new Panel { Children = { _loadingText, _message } },
        };
        _well = new Border
        {
            CornerRadius = new CornerRadius(Radius.Md), ClipToBounds = true,
            Child = new Panel { Children = { _pageHost, _loading } },
        };

        _browserSwitch = Ui.Button("Use my browser instead", Look.Ghost, Icons.External, Sizes.ControlMd);
        _browserSwitch.HorizontalAlignment = HorizontalAlignment.Center;
        _browserSwitch.Margin = new Thickness(0, Space.S2, 0, 0);
        _browserSwitch.Click += (_, _) => ShowBrowser(openNow: true);

        // ---- The browser path ----
        var open = Ui.Button("Open sign-in page", Look.Secondary, Icons.External);
        open.Click += (_, _) => OpenInBrowser();
        _paste = new TextBox
        {
            Height = Sizes.ControlLg, CornerRadius = new CornerRadius(Radius.Md), FontSize = TypeScale.Body,
            VerticalContentAlignment = VerticalAlignment.Center, AcceptsReturn = false,
            PlaceholderText = "https://login.live.com/oauth20_desktop.srf?code=...",
        };
        _paste.TextChanged += (_, _) =>
        {
            if (_paste.Text != _errorFor) SetPasteError(null);
            _finish!.IsEnabled = !string.IsNullOrWhiteSpace(_paste.Text);
        };
        _paste.KeyDown += (_, e) => { if (e.Key == Key.Enter) { e.Handled = true; _ = FinishFromPasteAsync(); } };
        _pasteError.IsVisible = false;
        _finish = Ui.Button("Finish sign-in", Look.Primary);
        _finish.HorizontalAlignment = HorizontalAlignment.Stretch;
        _finish.IsEnabled = false;
        _finish.Click += (_, _) => _ = FinishFromPasteAsync();
        _browser = Ui.Column(0,
            Step("1", "Sign in on Microsoft's page in your browser."),
            open.Margin(28, Space.S2, 0, Space.S4),
            Step("2", "When the page goes blank, copy its whole address from the address bar and paste it here."),
            _paste.Margin(0, Space.S2, 0, 0),
            _pasteError.Margin(0, 6, 0, 0),
            _finish.Margin(0, Space.S4, 0, 0));
        _browser.IsVisible = false;

        Children.Add(Ui.Column(0, _header, _well, _browserSwitch, _browser));
    }

    private static Control Step(string n, string text)
    {
        var num = Ui.Capsule(new Border
        {
            Width = 20, Height = 20, Background = Palette.GhostHover, VerticalAlignment = VerticalAlignment.Top,
            Child = Ui.Text(n, TypeScale.Caption, FontWeight.SemiBold, Palette.Text).Align(HorizontalAlignment.Center),
        });
        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*"), ColumnSpacing = Space.S2 };
        g.Children.Add(num.At(0));
        g.Children.Add(Ui.Wrap(Ui.Body(text)).At(1));
        return g;
    }

    // ---- Real runs ----

    private void Start()
    {
        _auth = new MicrosoftAuthenticator();
        (_authUrl, _verifier, _state) = _auth.BuildTestAuthorizationRequest();
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
            view.NavigationStarted += (_, e) =>
            {
                if (e.Request is { } uri && HandleRedirect(uri.ToString())) e.Cancel = true;
            };
            view.NavigationCompleted += (_, _) => { if (!_finishing && !_message.IsVisible) ShowPage(true); };
            _webView = view;
            _pageHost.Children.Add(view);
            view.Navigate(new Uri(_authUrl));
            // A web view that never starts (no libwebkit2gtk, no display) leaves the card loading: after a while
            // the browser path takes over by itself, so a sign-in is never stuck.
            DispatcherTimer.RunOnce(() =>
            {
                if (!_done && !_finishing && !_pageShown && !_message.IsVisible && _well.IsVisible) ShowBrowser(openNow: false);
            }, TimeSpan.FromSeconds(8));
        }
        catch (Exception)
        {
            ShowBrowser(openNow: false);
        }
        // WPF waits five minutes for an answer.
        DispatcherTimer.RunOnce(() =>
        {
            if (!_done && !_finishing) Fail("Sign-in timed out - no response after 5 minutes.");
        }, TimeSpan.FromMinutes(5));
    }

    /// <summary>The redirect, from the web view or a pasted address. True = it was ours (stop that navigation).</summary>
    private bool HandleRedirect(string address)
    {
        if (!address.StartsWith(MicrosoftAuthenticator.TestRedirectUri, StringComparison.Ordinal)) return false;
        var result = ReadRedirect(address, _state);
        switch (result.Kind)
        {
            case RedirectKind.NotOurs:
                return true;
            case RedirectKind.Code:
                _ = CompleteAsync(result.Value);
                return true;
            default:
                Fail(result.Value);
                return true;
        }
    }

    public enum RedirectKind { NotOurs, Code, Failed }

    public readonly record struct Redirect(RedirectKind Kind, string Value);

    /// <summary>The WPF panel's redirect rules (one copy for both paths, kept beside the parse).</summary>
    public static Redirect ReadRedirect(string address, string expectedState)
    {
        Uri uri;
        try { uri = new Uri(address.Trim()); }
        catch (UriFormatException) { return new Redirect(RedirectKind.NotOurs, ""); }
        var query = ParseQuery(uri.Query);
        var hasState = query.TryGetValue("state", out var state);
        if (hasState && state != expectedState) return new Redirect(RedirectKind.NotOurs, "");
        query.TryGetValue("code", out var code);
        if (!hasState)
            return string.IsNullOrEmpty(code)
                ? new Redirect(RedirectKind.NotOurs, "")
                : new Redirect(RedirectKind.Failed, "Microsoft sign-in didn't finish. Please try again.");
        if (!string.IsNullOrEmpty(code)) return new Redirect(RedirectKind.Code, code);
        return new Redirect(RedirectKind.Failed, query.TryGetValue("error", out var err) && err == "access_denied"
            ? "Sign-in was cancelled on Microsoft's page."
            : "Microsoft sign-in didn't finish. Please try again.");
    }

    private static Dictionary<string, string> ParseQuery(string query)
    {
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var part in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = part.IndexOf('=');
            var key = Uri.UnescapeDataString(eq < 0 ? part : part[..eq]);
            var value = eq < 0 ? "" : Uri.UnescapeDataString(part[(eq + 1)..].Replace('+', ' '));
            map[key] = value;
        }
        return map;
    }

    private void OpenInBrowser()
    {
        if (_authUrl.Length > 0) ExternalLink.Open(new Uri(_authUrl));
    }

    private async Task FinishFromPasteAsync()
    {
        var text = _paste.Text?.Trim() ?? "";
        if (text.Length == 0 || _finishing) return;
        if (!text.StartsWith(MicrosoftAuthenticator.TestRedirectUri, StringComparison.Ordinal))
        {
            PasteError("That isn't the sign-in address. Copy the whole address of the blank page Microsoft ends on.");
            return;
        }
        var result = ReadRedirect(text, _state);
        switch (result.Kind)
        {
            case RedirectKind.Code:
                await CompleteAsync(result.Value);
                break;
            case RedirectKind.NotOurs when text.Contains("state=", StringComparison.Ordinal):
                PasteError("That address is from a different sign-in. Open the page again and use the newest one.");
                break;
            case RedirectKind.NotOurs:
                PasteError("That address has no sign-in code yet. Copy it once the page has finished.");
                break;
            default:
                Fail(result.Value);
                break;
        }
    }

    private void PasteError(string text)
    {
        _errorFor = _paste.Text;
        SetPasteError(text);
    }

    /// <summary>The line under the paste box (null clears it); the box's edge turns red with it.</summary>
    private void SetPasteError(string? text)
    {
        _pasteError.Text = text ?? "";
        _pasteError.IsVisible = text != null;
        if (text != null) _paste.BorderBrush = Palette.Danger;
        else
        {
            _paste.ClearValue(TextBox.BorderBrushProperty);
            _errorFor = null;
        }
    }

    /// <summary>
    /// Microsoft's page on show (true): the well takes the page's height and the cover lifts. Anything else
    /// (loading, finishing, an outcome): the cover is up and the well fits it (WPF ShowPage). Once the page has
    /// shown, the web view is hidden under the cover: it is a native child window that would draw over the text.
    /// </summary>
    private void ShowPage(bool page)
    {
        if (page) _pageShown = true;
        _well.Height = page ? Sizes.SignInPage : double.NaN;
        _loading.IsVisible = !page;
        if (_pageShown && _webView != null) _webView.IsVisible = page;
    }

    private bool _pageShown;

    private async Task CompleteAsync(string code)
    {
        if (_finishing || _done || _auth == null) return;
        _finishing = true;
        ShowLoading("Finishing sign-in...");
        try
        {
            var result = await _auth.CompleteTestSignInAsync(code, _verifier, _cts.Token);
            var outcome = AccountDrawer.Save(result);
            _done = true;
            if (outcome == null)
            {
                _window.CloseDialog();
                _window.CloseDrawer();
            }
            else ShowMessage(outcome.Title, outcome.Text);
        }
        catch (OperationCanceledException) { /* closed by the player */ }
        catch (TimeoutException) { Fail("Sign-in timed out - no response after 5 minutes."); }
        catch (MicrosoftAuthException ex) { Fail(ex.Message); }
        catch (HttpRequestException) { Fail("Couldn't reach Microsoft. Check your internet connection and try again."); }
        catch (Exception) { Fail("Sign-in didn't finish. Please try again."); }
    }

    private void Fail(string message)
    {
        _done = true;
        AccountDrawer.Open?.ShowNote(message, error: true);
        ShowMessage("Couldn't add this account", message);
    }

    private void Closed()
    {
        _done = true;
        _cts.Cancel();
        if (_webView is NativeWebView view)
        {
            try { view.Stop(); } catch (Exception) { /* already gone */ }
        }
        _pageHost.Children.Clear();
        AccountDrawer.Open?.SetBusy(false);
    }

    // ---- States (also what the render run shows) ----

    /// <summary>The page well with its loading line over it ("Loading sign-in..." / "Finishing sign-in...").</summary>
    public void ShowLoading(string text = "Loading sign-in...")
    {
        _loadingText.Text = text;
        _loadingText.IsVisible = true;
        _message.IsVisible = false;
        _well.IsVisible = true;
        ShowPage(false);
        _browserSwitch.IsVisible = !_finishing && text == "Loading sign-in...";
        _browser.IsVisible = false;
        _header.IsVisible = true;
    }

    /// <summary>The end of a sign-in that didn't simply work: a title, the reason, and OK.</summary>
    public void ShowMessage(string title, string text)
    {
        _finishing = false;
        _messageTitle.Text = title;
        _messageText.Text = text;
        _loadingText.IsVisible = false;
        _message.IsVisible = true;
        _well.IsVisible = true;
        ShowPage(false);
        _browserSwitch.IsVisible = false;
        _browser.IsVisible = false;
        if (_webView != null) _pageHost.Children.Clear();
    }

    /// <summary>The browser path: open the page in the system browser, paste the address it ends on.</summary>
    public void ShowBrowser(bool openNow)
    {
        if (_done) return;
        _well.IsVisible = false;
        _browserSwitch.IsVisible = false;
        _message.IsVisible = false;
        _browser.IsVisible = true;
        if (_webView != null) _pageHost.Children.Clear();
        if (openNow) OpenInBrowser();
        Dispatcher.UIThread.Post(() => _paste.Focus(), DispatcherPriority.Input);
    }

    /// <summary>Render runs: a pasted address with an error under it.</summary>
    public void ShowPasteError(string pasted, string error)
    {
        _paste.Text = pasted;
        PasteError(error);
    }
}
