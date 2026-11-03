using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Updates;

namespace OriginLauncher.Mac;

/// <summary>
/// `--update-proof &lt;feed url&gt; --root &lt;dir&gt; --expect swap|bad-hash|no-hash|refuse` (CI only, no window): the
/// launcher's real self-update path, the same UpdateService calls the top bar's chip makes, against a FAKE release that CI
/// serves from a scratch folder on this machine. The feed must be http on a loopback IP literal (anything else is refused),
/// all data goes to the scratch root, and the app that gets swapped is the one this binary runs from (CI runs it from a
/// throwaway copy of Origin.app). Prints PASS/FAIL lines; exits 0 only when the expected outcome happened.
///   swap      offered, gates Play, downloads, hash matches, unpacks, writes and starts the swap script, quits. CI then
///             checks the swap itself (the new app in place, nothing left over, the reopen command issued, the app runs).
///   bad-hash  offered, then the download fails its SHA-256: refused, nothing unpacked, Play released at once.
///   no-hash   a release with no .sha256 and no digest: never offered, never gates Play.
///   refuse    run from a translocated copy or a folder it can't write to: never offered, never gates Play, nothing
///             downloaded, and UpdateService.Blocked says to move it to Applications.
/// </summary>
internal static class UpdateProof
{
    private static int _failures;

    public static int Run(string feedUrl, string? root, string? expect)
    {
        if (root == null || expect is not ("swap" or "bad-hash" or "no-hash" or "refuse"))
        {
            Console.Error.WriteLine("Usage: OriginLauncher --update-proof <http://127.0.0.1:port/latest.json> --root <dir> --expect swap|bad-hash|no-hash|refuse");
            return 2;
        }
        // An IP literal only (127.0.0.1 or ::1): a name like "localhost" goes through the resolver, which could be pointed elsewhere.
        if (!Uri.TryCreate(feedUrl, UriKind.Absolute, out var feed) || feed.Scheme != Uri.UriSchemeHttp
            || feed.HostNameType is not (UriHostNameType.IPv4 or UriHostNameType.IPv6)
            || !System.Net.IPAddress.TryParse(feed.DnsSafeHost, out var ip) || !System.Net.IPAddress.IsLoopback(ip))
        {
            Console.Error.WriteLine("update-proof: the feed must be http on a loopback IP (127.0.0.1 or [::1]); refusing " + feedUrl);
            return 2;
        }
        OriginPaths.UseScratchRoot(root);
        UpdateService.FeedUrl = feed.AbsoluteUri;
        UpdateService.ProofFeed = true; // the scratch root turns updates off; this run proves them against the local feed
        // No background download here: the first check would start one that races this run's own click (a bad-hash
        // release could be withdrawn before the click). The click path below runs the same download and verify code.
        UpdateService.Prefetch = false;
        Console.WriteLine($"update-proof: expect {expect}, feed {feed.AbsoluteUri}, running {UpdateService.CurrentVersion} from {AppContext.BaseDirectory}");
        try
        {
            RunAsync(expect).GetAwaiter().GetResult();
        }
        catch (Exception e)
        {
            Fail($"unexpected {e.GetType().Name}: {e.Message}");
        }
        Console.WriteLine(_failures == 0 ? "RESULT PASS" : $"RESULT FAIL ({_failures})");
        return _failures == 0 ? 0 : 1;
    }

    private static async Task RunAsync(string expect)
    {
        await UpdateService.CheckAsync();
        var offered = UpdateService.Available;
        var gatedWhenOffered = !UpdateService.IsDevBuild; // a CI-stamped build is gated by any offer; a dev build never is

        switch (expect)
        {
            case "refuse":
                Check(offered == null, "not offered");
                Check(!UpdateService.UpdateRequired, "Play not gated");
                Check(UpdateService.Blocked is { } b && b.Reason.Contains("Applications", StringComparison.Ordinal),
                    $"blocked with a move-to-Applications reason: {UpdateService.Blocked?.Reason}");
                Check(NoDownloads(), "nothing downloaded");
                return;

            case "no-hash":
                Check(offered == null, "not offered");
                Check(!UpdateService.UpdateRequired, "Play not gated");
                Check(UpdateService.Blocked == null, "not reported as blocked");
                Check(UpdateLog().Contains("published no SHA-256", StringComparison.Ordinal), "refused for having no SHA-256 (update.log)");
                Check(NoDownloads(), "nothing downloaded");
                return;
        }

        // swap and bad-hash: offered first, the way the chip lights up.
        Check(offered != null, $"offered {offered?.Version}");
        Check(UpdateService.UpdateRequired == gatedWhenOffered, $"Play gated while offered: {UpdateService.UpdateRequired} (stamped build: {gatedWhenOffered})");
        Check(UpdateService.Blocked == null, "not blocked here");
        if (offered == null) return;

        var progress = new Recorder();
        if (expect == "swap")
        {
            // The quit is the last step: by then the script is running and waiting for this process to be gone.
            UpdateService.Shutdown = () =>
            {
                var stage = Path.Combine(OriginPaths.Updates, offered.Version.ToString());
                Check(progress.Saw(UpdateService.UpdateStage.Downloading) && progress.Saw(UpdateService.UpdateStage.Installing)
                      && progress.Saw(UpdateService.UpdateStage.Restarting), $"stages {progress}");
                Check(Directory.EnumerateDirectories(stage, "*.app").Any(), "new app unpacked into the stage");
                Check(File.Exists(Path.Combine(OriginPaths.Updates, "apply-update.sh")), "swap script written");
                Check(!Directory.EnumerateFiles(OriginPaths.Updates, "*.zip*").Any(), "downloaded zip removed after unpacking");
                Console.WriteLine("update-proof: quitting for the swap (pid " + Environment.ProcessId + ")");
                Console.WriteLine(_failures == 0 ? "RESULT PASS" : $"RESULT FAIL ({_failures})");
                Console.Out.Flush();
                Environment.Exit(_failures == 0 ? 0 : 1);
            };
            await UpdateService.DownloadAndRestartAsync(progress);
            Fail("DownloadAndRestartAsync returned without quitting");
            return;
        }

        // bad-hash
        try
        {
            await UpdateService.DownloadAndRestartAsync(progress);
            Fail("a download with the wrong SHA-256 was installed");
        }
        catch (UpdateVerificationException e)
        {
            Check(true, $"refused: {e.Message}");
        }
        Check(UpdateService.Available == null, "update withdrawn");
        Check(!UpdateService.UpdateRequired, "Play released at once");
        Check(NoDownloads(), "no zip or .part left");
        var stageDir = Path.Combine(OriginPaths.Updates, offered.Version.ToString());
        Check(!Directory.Exists(stageDir) || !Directory.EnumerateFileSystemEntries(stageDir).Any(), "nothing unpacked");
        Check(!File.Exists(Path.Combine(OriginPaths.Updates, "apply-update.sh")), "no swap script");
    }

    private static bool NoDownloads() =>
        !Directory.Exists(OriginPaths.Updates) || !Directory.EnumerateFiles(OriginPaths.Updates, "*.zip*").Any();

    private static string UpdateLog()
    {
        var path = Path.Combine(OriginPaths.Logs, "update.log");
        return File.Exists(path) ? File.ReadAllText(path) : "";
    }

    private static void Check(bool ok, string what)
    {
        Console.WriteLine((ok ? "PASS " : "FAIL ") + what);
        if (!ok) _failures++;
    }

    private static void Fail(string what) => Check(false, what);

    // Records every stage synchronously (Progress<T> would post them to the pool, out of order).
    private sealed class Recorder : IProgress<UpdateService.UpdateProgress>
    {
        private readonly List<UpdateService.UpdateStage> _stages = new();

        public void Report(UpdateService.UpdateProgress value)
        {
            lock (_stages)
                if (_stages.Count == 0 || _stages[^1] != value.Stage) _stages.Add(value.Stage);
        }

        public bool Saw(UpdateService.UpdateStage stage)
        {
            lock (_stages) return _stages.Contains(stage);
        }

        public override string ToString()
        {
            lock (_stages) return string.Join(" > ", _stages);
        }
    }
}
