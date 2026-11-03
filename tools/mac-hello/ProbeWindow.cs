using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform;
using System.Diagnostics;

namespace MacHello;

// One window, one NativeWebView (WKWebView on macOS). Mirrors the Windows sign-in panel:
// private data store, watch every navigation start, cancel the redirect before it loads.
// No credentials are ever typed: every redirect here is simulated or a Microsoft error reply.
internal sealed class ProbeWindow : Window
{
    private readonly NativeWebView _web = new();
    private readonly string? _probeDir;
    private readonly TaskCompletionSource _adapter = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private TaskCompletionSource<string> _catch = NewCatch();
    private string _expectedState = "";
    private int _redirectLoaded;
    private int _failedChecks;
    private bool _nonPersistentRequested;

    private static TaskCompletionSource<string> NewCatch() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    public ProbeWindow(string? probeDir)
    {
        _probeDir = probeDir;
        Title = "Origin Hello";
        Width = 1000;
        Height = 760;

        _web.EnvironmentRequested += OnEnvironmentRequested;
        _web.AdapterCreated += (_, _) => _adapter.TrySetResult();
        _web.NavigationStarted += OnNavigationStarted;
        _web.NavigationCompleted += OnNavigationCompleted;

        var header = new TextBlock
        {
            Text = "Origin Hello - Mac packaging and sign-in probe",
            Margin = new Avalonia.Thickness(16, 12),
            VerticalAlignment = VerticalAlignment.Center,
            Foreground = Brushes.White,
        };
        DockPanel.SetDock(header, Dock.Top);
        Content = new DockPanel { Children = { header, _web } };

        Opened += async (_, _) =>
        {
            if (_probeDir is null) { await ShowForAPerson(); return; }
            try { await RunProbe(_probeDir); }
            catch (Exception ex)
            {
                ProbeLog.Write("EXCEPTION " + ex);
                _failedChecks++;
            }
            ProbeLog.Write(_failedChecks == 0 ? "RESULT PASS" : $"RESULT FAIL failed={_failedChecks}");
            Program.ExitCode = _failedChecks == 0 ? 0 : 1;
            Close();
        };
    }

    private void OnEnvironmentRequested(object? sender, WebViewEnvironmentRequestedEventArgs e)
    {
        // Same job as WebView2's InPrivate profile on Windows: never a session to reuse.
        if (e is AppleWKWebViewEnvironmentRequestedEventArgs apple)
        {
            apple.NonPersistentDataStore = true;
            _nonPersistentRequested = true;
        }
        ProbeLog.Write($"ENV {e.GetType().Name} nonPersistentRequested={_nonPersistentRequested}");
    }

    private void OnNavigationStarted(object? sender, WebViewNavigationStartingEventArgs e)
    {
        var uri = e.Request?.AbsoluteUri ?? "";
        ProbeLog.Write("NAV-START " + Short(uri));
        if (!uri.StartsWith(AuthRequest.RedirectUri, StringComparison.Ordinal)) return;

        e.Cancel = true;
        var outcome = AuthRequest.Classify(uri, _expectedState);
        ProbeLog.Write($"CATCH e.Cancel=true outcome={outcome} uri={Short(uri)}");
        _catch.TrySetResult(outcome);
    }

    private void OnNavigationCompleted(object? sender, WebViewNavigationCompletedEventArgs e)
    {
        var uri = e.Request?.AbsoluteUri ?? "";
        ProbeLog.Write($"NAV-DONE ok={e.IsSuccess} {Short(uri)}");
        if (e.IsSuccess && uri.StartsWith(AuthRequest.RedirectUri, StringComparison.Ordinal))
            _redirectLoaded++;
    }

    private async Task ShowForAPerson()
    {
        var (url, state) = AuthRequest.Build();
        _expectedState = state;
        await Task.WhenAny(_adapter.Task, Task.Delay(TimeSpan.FromSeconds(20)));
        _web.Navigate(new Uri(url));
    }

    private async Task RunProbe(string dir)
    {
        var clock = Stopwatch.StartNew();
        Activate();

        // 1. The native web view exists and uses a throwaway data store.
        var created = await Task.WhenAny(_adapter.Task, Task.Delay(TimeSpan.FromSeconds(30))) == _adapter.Task;
        Check("adapter-created", created, $"info={_web.AdapterInfo}");
        var handle = (_web.TryGetPlatformHandle() as IAppleWKWebViewPlatformHandle)?.WKWebView ?? IntPtr.Zero;
        Check("wkwebview-handle", handle != IntPtr.Zero, $"descriptor={_web.TryGetPlatformHandle()?.HandleDescriptor}");
        if (handle == IntPtr.Zero) return;
        var persistent = AppleNative.IsDataStorePersistent(handle);
        Check("data-store-non-persistent", _nonPersistentRequested && !persistent, $"requested={_nonPersistentRequested} isPersistent={persistent}");

        // 2. The real authorize URL renders.
        var (url, state) = AuthRequest.Build();
        _expectedState = state;
        ProbeLog.Write("AUTH-URL " + Short(url));
        _web.Navigate(new Uri(url));

        string href = "", title = "", inputs = "0", length = "0";
        for (var i = 0; i < 60; i++)
        {
            await Task.Delay(1000);
            var ready = await Eval("document.readyState");
            href = await Eval("location.href");
            title = await Eval("document.title");
            inputs = await Eval("document.querySelectorAll('input').length");
            length = await Eval("((document.body && document.body.innerText) || '').length");
            if (ready.Contains("complete") && ParseInt(inputs) > 0 && ParseInt(length) > 20) break;
        }
        await Task.Delay(3000); // let the sign-in page finish its entrance before the picture
        var preview = await Eval("((document.body && document.body.innerText) || '').slice(0, 160)");
        ProbeLog.Write($"PAGE after={clock.ElapsedMilliseconds}ms href={Short(href)} title={title} inputs={inputs} textLength={length}");
        ProbeLog.Write("PAGE-TEXT " + preview.Replace('\n', ' ').Replace("\\n", " "));
        Check("page-rendered", href.Contains("login.") && ParseInt(inputs) > 0 && ParseInt(length) > 20,
            $"inputs={inputs} textLength={length}");

        // 3. Pictures: the web view's own snapshot (needs no screen-recording permission),
        //    plus a whole-screen shot taken by the job while the window is up.
        var snapshotPath = Path.Combine(dir, "webview.png");
        var snap = await AppleNative.SnapshotToPngAsync(handle, snapshotPath, TimeSpan.FromSeconds(20));
        var snapSize = File.Exists(snapshotPath) ? new FileInfo(snapshotPath).Length : 0;
        Check("webview-snapshot", snap && snapSize > 10_000, $"bytes={snapSize}");

        File.WriteAllText(Path.Combine(dir, "shot-now"), "");
        for (var i = 0; i < 30 && !File.Exists(Path.Combine(dir, "shot-done")); i++) await Task.Delay(1000);
        ProbeLog.Write("SCREEN job-shot-done=" + File.Exists(Path.Combine(dir, "shot-done")));

        // 4. Simulated final redirect from script, with our state.
        var fake = $"{AuthRequest.RedirectUri}?code=SIMULATED-NOT-A-REAL-CODE&state={state}&lc=1033";
        var outcome = await NavigateAndWaitForCatch(() => ScriptNavigate(fake));
        Check("js-redirect-caught", outcome == "code", $"outcome={outcome}");
        await AssertStayedOnPage("js-redirect-stayed");

        // 5. Same redirect with someone else's state must be caught and ignored.
        outcome = await NavigateAndWaitForCatch(() =>
            ScriptNavigate($"{AuthRequest.RedirectUri}?code=SIMULATED-NOT-A-REAL-CODE&state=not-ours"));
        Check("wrong-state-ignored", outcome == "ignored-wrong-state", $"outcome={outcome}");

        // 6. A REAL server 302 from Microsoft. The real sign-in ends with a server redirect, and
        //    script navigation doesn't prove that path. An unknown scope makes login.live.com answer
        //    "302 -> oauth20_desktop.srf?error=invalid_scope&state=..." with no sign-in involved.
        var (badUrl, badState) = AuthRequest.Build("NOT_A_REAL_SCOPE");
        _expectedState = badState;
        outcome = await NavigateAndWaitForCatch(() => { _web.Navigate(new Uri(badUrl)); return Task.CompletedTask; });
        Check("server-302-caught", outcome == "error=invalid_scope", $"outcome={outcome}");
        await AssertStayedOnPage("server-302-stayed");

        Check("redirect-page-never-loaded", _redirectLoaded == 0, $"loads={_redirectLoaded}");
        ProbeLog.Write($"DONE after={clock.ElapsedMilliseconds}ms");
    }

    private async Task<string> NavigateAndWaitForCatch(Func<Task> navigate)
    {
        _catch = NewCatch();
        await navigate();
        var hit = await Task.WhenAny(_catch.Task, Task.Delay(TimeSpan.FromSeconds(30)));
        return hit == _catch.Task ? _catch.Task.Result : "timeout-no-catch";
    }

    private Task ScriptNavigate(string target) =>
        Eval($"(function(){{ window.location.href = '{target}'; return 'sent'; }})()");

    private async Task AssertStayedOnPage(string name)
    {
        await Task.Delay(2500);
        var href = await Eval("location.href");
        // StartsWith, not Contains: the authorize page's own URL carries the redirect as its
        // redirect_uri parameter, so a Contains check fails while still on the sign-in page.
        var stayed = !href.Trim('"').StartsWith(AuthRequest.RedirectUri, StringComparison.Ordinal);
        Check(name, stayed, "href=" + Short(href));
    }

    private async Task<string> Eval(string script)
    {
        try { return await _web.InvokeScript(script) ?? ""; }
        catch (Exception ex) { return "<script error: " + ex.Message + ">"; }
    }

    private void Check(string name, bool ok, string detail)
    {
        if (!ok) _failedChecks++;
        ProbeLog.Write($"CHECK {(ok ? "PASS" : "FAIL")} {name} {detail}");
    }

    private static int ParseInt(string s) => int.TryParse(s.Trim('"', ' '), out var n) ? n : 0;

    private static string Short(string s) => s.Length <= 200 ? s : s[..200] + "...";
}
