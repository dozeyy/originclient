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
public partial class MicrosoftSignInPanel : UserControl
{
    public event EventHandler<AuthResult>? SignInSucceeded;
    public event EventHandler<string>? SignInFailed;
    public event EventHandler? Cancelled;

    private readonly CancellationTokenSource _cts = new();

    public MicrosoftSignInPanel()
    {
        InitializeComponent();
        Loaded += MicrosoftSignInPanel_Loaded;
    }

    private async void MicrosoftSignInPanel_Loaded(object sender, RoutedEventArgs e)
    {
        try
        {
            await WebView.EnsureCoreWebView2Async();
            WebView.CoreWebView2.NavigationCompleted += (_, _) => LoadingOverlay.Visibility = Visibility.Collapsed;

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
            SignInFailed?.Invoke(this, ex.Message);
        }
        catch (Exception ex)
        {
            SignInFailed?.Invoke(this, $"Sign-in failed: {ex.Message}");
        }
    }

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
        var (authUrl, codeVerifier) = authenticator.BuildTestAuthorizationRequest();

        var codeSource = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);

        void OnNavigationStarting(object? s, CoreWebView2NavigationStartingEventArgs args)
        {
            if (!args.Uri.StartsWith(MicrosoftAuthenticator.TestRedirectUri, StringComparison.Ordinal)) return;
            args.Cancel = true;

            var query = ParseQuery(new Uri(args.Uri).Query);
            if (query.TryGetValue("code", out var code))
                codeSource.TrySetResult(code);
            else
                codeSource.TrySetException(new MicrosoftAuthException("login",
                    $"Microsoft sign-in did not return an authorization code ({(query.TryGetValue("error", out var err) ? err : "unknown error")})."));
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
}
