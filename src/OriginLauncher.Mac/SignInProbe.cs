using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Platform;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac;

/// <summary>
/// <c>--signin-probe &lt;dir&gt;</c> (CI only, on a Mac runner): proves the real Microsoft sign-in card works on this
/// Mac without any account. It opens a small window holding the card, never the launcher, under a throwaway data root
/// (OriginPaths.UseScratchRoot), and nobody is ever signed in:
///  1. the web view: WKWebView comes up with a non-persistent data store, Microsoft's real authorize page draws, a
///     redirect with someone else's state is ignored, and a simulated redirect with ours is caught (a made-up code that
///     Microsoft then refuses, so the card reports a failure), without the redirect page ever loading;
///  2. a real server-side redirect: the authorize page asked for a scope that doesn't exist answers with Microsoft's
///     own 302 to the redirect address, which the card catches as an error;
///  3. sign in with a code: Microsoft's live endpoint hands out a code and the card shows it, then the card is closed
///     and that cancels quietly.
/// Secrets: the user code is logged by its length only; no redirect or authorize address from the card is logged.
/// Writes probe.log (one CHECK line each, then RESULT PASS or RESULT FAIL); exit 0 or 1.
/// </summary>
internal static class SignInProbe
{
    internal static string? Dir { get; set; }

    private static readonly StringBuilder Log = new();
    private static int _failures;

    internal static Window Start(IClassicDesktopStyleApplicationLifetime desktop)
    {
        Directory.CreateDirectory(Dir!);
        OriginPaths.UseScratchRoot(Path.Combine(Path.GetTempPath(), "origin-signin-probe-" + Guid.NewGuid().ToString("N")));
        var window = new Window { Title = "Origin sign-in probe", Width = 460, Height = 720 };
        window.Opened += (_, _) => Dispatcher.UIThread.Post(async () =>
        {
            try { await RunAsync(window); }
            catch (Exception ex) { Check("probe-ran", false, ex.GetType().Name); }
            Finish(desktop);
        });
        return window;
    }

    private static async Task RunAsync(Window window)
    {
        await WebPanelAsync(window);
        await ServerRedirectAsync(window);
        await CodePanelAsync(window);
    }

    // ---- 1. the web view and a simulated redirect ----
    private static async Task WebPanelAsync(Window window)
    {
        var (panel, failures) = Mount(window);
        var outcomes = new List<RedirectOutcome>();
        panel.ProbeRedirect += outcomes.Add;

        var adapter = await Within(panel.ProbeAdapter, 30);
        Check("adapter-created", adapter, adapter ? "NativeWebView adapter up" : "no adapter in 30 s");
        if (!adapter) { panel.Dispose(); return; }

        var handle = panel.ProbeWeb?.TryGetPlatformHandle() as IAppleWKWebViewPlatformHandle;
        var wk = handle?.WKWebView ?? IntPtr.Zero;
        Check("wkwebview-handle", wk != IntPtr.Zero, wk != IntPtr.Zero ? "a WKWebView" : "no WKWebView handle");
        bool? persistent = wk != IntPtr.Zero ? AppleNative.IsDataStorePersistent(wk) : null;
        Check("data-store-non-persistent", panel.NonPersistentRequested && persistent == false,
            $"requested={panel.NonPersistentRequested} websiteDataStore.isPersistent={persistent?.ToString() ?? "unknown"}");

        var shown = await Until(() => panel.ProbePageShown, 60);
        var authHost = Uri.TryCreate(panel.ProbeAuthUrl, UriKind.Absolute, out var auth) ? auth.Host : "";
        Check("page-rendered", shown && authHost == "login.live.com", $"page shown={shown} host={authHost}");
        if (!shown) { panel.Dispose(); return; }

        // Someone else's redirect (wrong state): ignored, the card keeps waiting.
        panel.ProbeWeb!.Navigate(new Uri(MicrosoftAuthenticator.TestRedirectUri + "?code=PROBE_NOT_A_CODE&state=not-this-cards-state"));
        var ignored = await Until(() => outcomes.Contains(RedirectOutcome.Ignore), 15);
        Check("wrong-state-ignored", ignored && failures.Count == 0, $"outcomes={string.Join(",", outcomes)} failures={failures.Count}");

        // Ours: caught, the made-up code goes to Microsoft, which refuses it, and the card says sign-in failed.
        panel.ProbeWeb!.Navigate(new Uri(MicrosoftAuthenticator.TestRedirectUri + "?code=PROBE_NOT_A_CODE&state=" + panel.ProbeState));
        var caught = await Until(() => outcomes.Contains(RedirectOutcome.Code), 15);
        var failed = await Until(() => failures.Count > 0, 45);
        Check("simulated-redirect-caught", caught && failed,
            $"outcomes={string.Join(",", outcomes)} card said=\"{failures.FirstOrDefault()}\"");

        await Task.Delay(2000);
        Check("redirect-page-never-loaded", panel.RedirectPageLoads == 0, $"redirect page loads={panel.RedirectPageLoads}");
        panel.Dispose();
    }

    // ---- 2. Microsoft's own 302 to the redirect address ----
    private static async Task ServerRedirectAsync(Window window)
    {
        var (panel, failures) = Mount(window);
        var outcomes = new List<RedirectOutcome>();
        panel.ProbeRedirect += outcomes.Add;
        var ready = await Within(panel.ProbeAdapter, 30) && await Until(() => panel.ProbePageShown, 60);
        if (!ready)
        {
            Check("server-302-caught", false, "the page never came up");
            panel.Dispose();
            return;
        }
        // The card's own request with a scope that doesn't exist: login.live.com answers with a 302 to the redirect
        // address carrying error=invalid_scope and the card's state.
        var badScope = Regex.Replace(panel.ProbeAuthUrl, "([?&]scope=)[^&]*", "$1NOT_A_REAL_SCOPE");
        panel.ProbeWeb!.Navigate(new Uri(badScope));
        var error = await Until(() => outcomes.Contains(RedirectOutcome.Error), 30);
        var failed = await Until(() => failures.Count > 0, 10);
        Check("server-302-caught", error && failed, $"outcomes={string.Join(",", outcomes)} card said=\"{failures.FirstOrDefault()}\"");
        Check("server-302-page-never-loaded", panel.RedirectPageLoads == 0, $"redirect page loads={panel.RedirectPageLoads}");
        panel.Dispose();
    }

    // ---- 3. sign in with a code, then close ----
    private static async Task CodePanelAsync(Window window)
    {
        var (panel, failures) = Mount(window);
        await Within(panel.ProbeAdapter, 30);
        panel.ProbeUseCode();
        var shown = await Until(() => panel.ProbeCodeShown || panel.ProbeCodeTask?.IsCompleted == true || failures.Count > 0, 60);
        var codeShown = shown && panel.ProbeCodeShown;
        // The code itself is never logged: only that there is one.
        Check("code-shown", codeShown && panel.ProbeUserCode.Length > 0,
            codeShown ? $"code of {panel.ProbeUserCode.Length} characters on screen" : $"no code (card said=\"{failures.FirstOrDefault()}\")");
        var verify = Uri.TryCreate(panel.ProbeVerificationUri, UriKind.Absolute, out var v) ? v : null;
        Check("verification-page-https", verify?.Scheme == Uri.UriSchemeHttps, $"page={verify?.Scheme}://{verify?.Host}");

        panel.ProbeCancel();
        var stopped = panel.ProbeCodeTask != null && await Within(panel.ProbeCodeTask, 10);
        await Task.Delay(500);
        Check("code-cancel-quiet", stopped && failures.Count == 0, $"stopped={stopped} failures={failures.Count}");
        panel.Dispose();
    }

    // ---- helpers ----

    private static (MicrosoftSignInPanel Panel, List<string> Failures) Mount(Window window)
    {
        var panel = new MicrosoftSignInPanel(pictureOnly: false);
        var failures = new List<string>();
        panel.SignInFailed += (_, message) => failures.Add(message);
        // A probe never finishes a sign-in; if one somehow did, nothing is stored (no AccountSwitcherPanel here).
        panel.SignInSucceeded += (_, _) => Check("never-signed-in", false, "a sign-in finished");
        window.Content = panel;
        return (panel, failures);
    }

    private static async Task<bool> Within(Task task, int seconds) =>
        await Task.WhenAny(task, Task.Delay(TimeSpan.FromSeconds(seconds))) == task;

    private static async Task<bool> Until(Func<bool> condition, int seconds)
    {
        var until = DateTime.UtcNow.AddSeconds(seconds);
        while (DateTime.UtcNow < until)
        {
            if (condition()) return true;
            await Task.Delay(100);
        }
        return condition();
    }

    private static void Check(string name, bool ok, string detail)
    {
        if (!ok) _failures++;
        var line = $"CHECK {name}: {(ok ? "PASS" : "FAIL")} - {detail}";
        Log.AppendLine(line);
        Console.WriteLine(line);
    }

    private static void Finish(IClassicDesktopStyleApplicationLifetime desktop)
    {
        var result = _failures == 0 ? "RESULT PASS" : $"RESULT FAIL ({_failures} failed)";
        Log.AppendLine(result);
        Console.WriteLine(result);
        File.WriteAllText(Path.Combine(Dir!, "probe.log"), Log.ToString());
        desktop.Shutdown(_failures == 0 ? 0 : 1);
    }

    // [[[webView configuration] websiteDataStore] isPersistent], straight through the Objective-C runtime.
    private static class AppleNative
    {
        private const string ObjC = "/usr/lib/libobjc.A.dylib";

        [DllImport(ObjC)] private static extern IntPtr sel_registerName(string name);
        [DllImport(ObjC, EntryPoint = "objc_msgSend")] private static extern IntPtr Send(IntPtr receiver, IntPtr selector);
        [DllImport(ObjC, EntryPoint = "objc_msgSend")]
        [return: MarshalAs(UnmanagedType.I1)]
        private static extern bool SendBool(IntPtr receiver, IntPtr selector);

        internal static bool? IsDataStorePersistent(IntPtr wkWebView)
        {
            var configuration = Send(wkWebView, sel_registerName("configuration"));
            if (configuration == IntPtr.Zero) return null;
            var store = Send(configuration, sel_registerName("websiteDataStore"));
            return store == IntPtr.Zero ? null : SendBool(store, sel_registerName("isPersistent"));
        }
    }
}
