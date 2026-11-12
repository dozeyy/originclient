using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Net;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.Core.Versions;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Bug run 8's fixes that are pure logic: bad or no internet (NET-2, NET-3, NET-5), the Windows self-update script
/// (SEC-1), a user folder Java can't read (LOC-2), 1.8.9's options.txt bytes (LOC-3) and memory sizes (LOC-6).
/// Stub handlers and temp folders only, never the network.
/// </summary>
public sealed class BugRun8Tests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("bugrun8").FullName;

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch { /* best effort */ }
    }

    private sealed class Stub(Func<int, HttpResponseMessage> reply) : HttpMessageHandler
    {
        public int Calls;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) =>
            Task.FromResult(reply(Interlocked.Increment(ref Calls)));
    }

    private static readonly byte[] Jar = [0x50, 0x4B, 0x03, 0x04, 9, 8, 7, 6, 5, 4, 3, 2];
    private static readonly string JarSha512 = Convert.ToHexString(SHA512.HashData(Jar)).ToLowerInvariant();
    private const string JarUrl = "https://cdn.modrinth.com/data/P7dR8mSH/versions/x/fabric-api-0.141.6+1.21.11.jar";
    private const string JarName = "fabric-api-0.141.6+1.21.11.jar";

    // ---- NET-2: Modrinth downloads get the same tries as Mojang's files -----------------------------------------

    [Fact]
    public async Task A_modrinth_download_that_fails_once_is_retried_and_installed()
    {
        var mods = Directory.CreateDirectory(Path.Combine(_root, "mods")).FullName;
        var stub = new Stub(n => n == 1
            ? throw new HttpRequestException("connection reset")
            : new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Jar) });

        var result = await ModrinthFileGuard.TryDownloadAsync(new HttpClient(stub), JarUrl, JarName, JarSha512, mods, CancellationToken.None);

        Assert.Equal(ModrinthDownloadResult.Installed, result);
        Assert.Equal(2, stub.Calls);
        Assert.Equal(Jar, File.ReadAllBytes(Path.Combine(mods, JarName)));
        Assert.Empty(Directory.EnumerateFiles(mods, "*.download"));
    }

    [Fact]
    public async Task A_modrinth_download_that_keeps_failing_stops_after_three_tries()
    {
        var mods = Directory.CreateDirectory(Path.Combine(_root, "mods")).FullName;
        var stub = new Stub(_ => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable));

        var result = await ModrinthFileGuard.TryDownloadAsync(new HttpClient(stub), JarUrl, JarName, JarSha512, mods, CancellationToken.None);

        Assert.Equal(ModrinthDownloadResult.Failed, result);
        Assert.Equal(DownloadRetry.Attempts, stub.Calls);
        Assert.Empty(Directory.EnumerateFiles(mods));
    }

    [Fact]
    public async Task A_refused_file_is_not_retried()
    {
        var mods = Directory.CreateDirectory(Path.Combine(_root, "mods")).FullName;
        var stub = new Stub(_ => new HttpResponseMessage(HttpStatusCode.NotFound));

        Assert.Equal(ModrinthDownloadResult.Failed,
            await ModrinthFileGuard.TryDownloadAsync(new HttpClient(stub), JarUrl, JarName, JarSha512, mods, CancellationToken.None));
        Assert.Equal(1, stub.Calls);
    }

    [Fact]
    public async Task The_retry_policy_retries_only_network_trouble()
    {
        var tries = 0;
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => DownloadRetry.RunAsync(() =>
        {
            tries++;
            throw new UnauthorizedAccessException("denied");
        }, CancellationToken.None, _ => TimeSpan.Zero));
        Assert.Equal(1, tries);

        tries = 0;
        var value = await DownloadRetry.RunAsync(() =>
        {
            if (++tries < 3) throw new TimeoutException();
            return Task.FromResult(42);
        }, CancellationToken.None, _ => TimeSpan.Zero);
        Assert.Equal(42, value);
        Assert.Equal(3, tries);
    }

    [Fact]
    public void Fabric_api_is_present_only_as_an_enabled_jar()
    {
        var mods = Directory.CreateDirectory(Path.Combine(_root, "mods")).FullName;
        Assert.False(FabricApiInstaller.Present(mods));
        Assert.False(FabricApiInstaller.Present(Path.Combine(_root, "missing")));
        File.WriteAllBytes(Path.Combine(mods, "fabric-api-0.92.2+1.20.1.jar.disabled"), Jar);
        File.WriteAllBytes(Path.Combine(mods, "sodium-fabric-0.5.11+mc1.20.1.jar"), Jar);
        Assert.False(FabricApiInstaller.Present(mods));
        File.WriteAllBytes(Path.Combine(mods, "fabric-api-0.92.2+1.20.1.jar"), Jar);
        Assert.True(FabricApiInstaller.Present(mods));
    }

    [Fact]
    public void A_missing_fabric_api_stops_play_with_its_own_words_and_retry()
    {
        var failure = PlayFailure.From(new RequiredModMissingException("Fabric API"), "1.20.1");
        // The host is named (a Modrinth outage is not Mojang's); the mod is not: it would name the loader (mandate 1).
        Assert.DoesNotContain("Fabric", failure.Message);
        Assert.Contains("Modrinth", failure.Message);
        Assert.DoesNotContain("Minecraft's download servers", failure.Message);
        Assert.EndsWith(PlayFailure.RetryLine, failure.Message);
    }

    // ---- NET-3: the failure line never waits on GitHub ------------------------------------------------------------

    [Fact]
    public async Task No_connection_skips_the_update_recheck()
    {
        var asked = 0;
        Task Recheck() { asked++; return Task.CompletedTask; }

        foreach (var offline in new Exception[]
                 {
                     new MicrosoftAuthException(MicrosoftAuthException.UnreachableStage, MicrosoftAuthException.UnreachableMessage),
                     new HttpRequestException("No such host is known."),
                     new OfflineInstallException("1.21.1", new HttpRequestException("offline")),
                 })
        {
            var line = await LaunchFailureText.ForAsync(offline, "plain", "update", Recheck, () => false);
            Assert.Equal("plain", line);
        }
        Assert.Equal(0, asked);
    }

    [Fact]
    public async Task A_recheck_that_never_answers_is_cut_off_at_the_cap()
    {
        var never = new TaskCompletionSource();
        var watch = System.Diagnostics.Stopwatch.StartNew();

        var line = await LaunchFailureText.ForAsync(new InvalidOperationException("boom"), "plain", "update",
            () => never.Task, () => false, cap: TimeSpan.FromMilliseconds(200));

        Assert.Equal("plain", line);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(5), $"waited {watch.Elapsed}");
    }

    [Fact]
    public async Task An_outdated_launcher_still_says_update()
    {
        var line = await LaunchFailureText.ForAsync(new InvalidOperationException("boom"), "plain", "update",
            () => Task.CompletedTask, () => true);
        Assert.Equal("update", line);
    }

    // ---- NET-5: a failed mod-update check is not "nothing newer" --------------------------------------------------

    [Fact]
    public async Task A_failed_update_check_is_no_answer()
    {
        var down = new HttpClient(new Stub(_ => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable)));
        Assert.Null(await ModrinthUpdates.FindAsync(down, "1.21.1",
            new Dictionary<string, string> { ["a.jar"] = new string('a', 128) }, CancellationToken.None));
    }

    [Fact]
    public void A_failed_check_keeps_the_known_buttons_and_asks_again_soon()
    {
        var now = new DateTime(2026, 11, 5, 12, 0, 0, DateTimeKind.Utc);
        var offer = new ModUpdateOffer("sodium-extra-0.5.jar", "0.6", "sodium-extra-0.6.jar",
            "https://cdn.modrinth.com/data/x/versions/y/sodium-extra-0.6.jar", new string('c', 128));
        var previous = new Dictionary<string, ModUpdateOffer>(StringComparer.OrdinalIgnoreCase)
        {
            ["sodium-extra-0.5.jar"] = offer,
            ["gone.jar"] = offer with { FileName = "gone.jar" },
        };

        var kept = ModrinthUpdates.KeptAfterFailure(previous, ["sodium-extra-0.5.jar", "other.jar"]);

        Assert.Equal(offer, Assert.Single(kept).Value);
        Assert.Empty(ModrinthUpdates.KeptAfterFailure(null, ["a.jar"]));
        Assert.False(ModrinthUpdates.Stale(now, failed: true, now.AddMinutes(1)));
        Assert.True(ModrinthUpdates.Stale(now, failed: true, now.AddMinutes(2)));
        Assert.False(ModrinthUpdates.Stale(now, failed: false, now.AddHours(5)));
        Assert.True(ModrinthUpdates.Stale(now, failed: false, now.AddHours(6)));
    }

    // ---- SEC-1: the Windows swap script carries no path ----------------------------------------------------------

    [Fact]
    public void The_windows_update_script_is_ascii_and_names_no_path()
    {
        const string stage = @"C:\Users\Jürgen 100%\AppData\Local\OriginLauncher\updates\stage\";
        const string app = @"C:\Users\Jürgen 100%\AppData\Local\Programs\OriginLauncher\";
        const string exe = @"C:\Users\Jürgen 100%\AppData\Local\Programs\OriginLauncher\OriginLauncher.App.exe";
        var updates = Directory.CreateDirectory(Path.Combine(_root, "updates")).FullName;

        var psi = WindowsApplyScript.Prepare(updates, 4242, stage, app, exe);
        var bytes = File.ReadAllBytes(Path.Combine(updates, WindowsApplyScript.FileName));

        Assert.All(bytes, b => Assert.True(b < 0x80, $"non-ASCII byte {b:X2} in the script"));
        var text = Encoding.ASCII.GetString(bytes);
        Assert.Equal(WindowsApplyScript.Text, text);
        Assert.DoesNotContain("Jürgen", text);
        Assert.DoesNotContain("OriginLauncher", text);
        Assert.Contains("robocopy \"%ORIGIN_UPDATE_STAGE%\" \"%ORIGIN_UPDATE_APP%\"", text);
        Assert.Contains("start \"\" \"%ORIGIN_UPDATE_EXE%\"", text);

        // The values travel in the environment, exactly (trailing separators off the folders), and not on the command line.
        Assert.Equal("4242", psi.Environment[WindowsApplyScript.PidVariable]);
        Assert.Equal(stage.TrimEnd('\\'), psi.Environment[WindowsApplyScript.StageVariable]);
        Assert.Equal(app.TrimEnd('\\'), psi.Environment[WindowsApplyScript.AppVariable]);
        Assert.Equal(exe, psi.Environment[WindowsApplyScript.ExeVariable]);
        Assert.Equal("/d /c .\\" + WindowsApplyScript.FileName, psi.Arguments);
        Assert.Equal(updates, psi.WorkingDirectory);
        Assert.False(psi.UseShellExecute);
        Assert.True(psi.CreateNoWindow);
    }

    // ---- LOC-2: a user folder Java can't read ---------------------------------------------------------------------

    private const string Turkish = @"C:\Users\Şükrü\AppData\Local\OriginLauncher";
    private const string German = @"C:\Users\Jürgen\AppData\Local\OriginLauncher";

    [Fact]
    public void A_name_the_code_page_can_spell_needs_nothing()
    {
        Assert.True(JavaPathEncoding.Representable(German, 1252));
        Assert.True(JavaPathEncoding.Representable(Turkish, 1254));
        Assert.True(JavaPathEncoding.Representable(Turkish, 65001));
        var (need, shortRoot) = JavaPathEncoding.Decide(German, 1252, _ => throw new InvalidOperationException("not asked"));
        Assert.Equal(JavaPathEncoding.Need.None, need);
        Assert.Null(shortRoot);
    }

    [Fact]
    public void A_name_the_code_page_cant_spell_takes_the_short_path()
    {
        // "Ş" is not in Windows-1252: best-fit would hand Java "S", a folder that doesn't exist.
        Assert.False(JavaPathEncoding.Representable(Turkish, 1252));
        Assert.False(JavaPathEncoding.Representable(@"C:\Users\Дима\AppData", 1252));

        var (need, shortRoot) = JavaPathEncoding.Decide(Turkish, 1252, _ => @"C:\Users\SKR~1\AppData\Local\ORIGIN~1\");
        Assert.Equal(JavaPathEncoding.Need.ShortPath, need);
        Assert.Equal(@"C:\Users\SKR~1\AppData\Local\ORIGIN~1", shortRoot);
    }

    [Fact]
    public void No_usable_short_name_is_a_plain_refusal_not_damaged_files()
    {
        Assert.Equal(JavaPathEncoding.Need.Impossible, JavaPathEncoding.Decide(Turkish, 1252, _ => null).Need);
        Assert.Equal(JavaPathEncoding.Need.Impossible, JavaPathEncoding.Decide(Turkish, 1252, p => p).Need); // 8.3 off
        Assert.Equal(JavaPathEncoding.Need.Impossible, JavaPathEncoding.Decide(Turkish, 1252, _ => throw new IOException()).Need);

        var failure = PlayFailure.From(new JavaPathUnreadableException(Turkish), "1.21.1");
        Assert.DoesNotContain("damaged", failure.Message, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("Retry", failure.Message);
        Assert.Contains("user name", failure.Message);
    }

    [Fact]
    public void The_short_root_replaces_the_long_one_everywhere_in_the_launch()
    {
        var psi = new System.Diagnostics.ProcessStartInfo
        {
            FileName = Turkish + @"\runtimes\java-runtime-delta\bin\javaw.exe",
            WorkingDirectory = Turkish + @"\instances\1.21.1\accounts\x",
            Arguments = $"-Djava.library.path=\"{Turkish}\\instances\\1.21.1\\natives\" -cp \"{Turkish}\\libraries\\a.jar\" net.fabricmc.loader.impl.launch.knot.KnotClient --gameDir \"{Turkish}\\instances\\1.21.1\\accounts\\x\"",
        };

        JavaPathEncoding.Apply(psi, Turkish, @"C:\Users\SKR~1\AppData\Local\ORIGIN~1");

        Assert.DoesNotContain("Şükrü", psi.FileName + psi.WorkingDirectory + psi.Arguments, StringComparison.OrdinalIgnoreCase);
        Assert.StartsWith(@"C:\Users\SKR~1\AppData\Local\ORIGIN~1\runtimes", psi.FileName);
        Assert.Contains(@"--gameDir ""C:\Users\SKR~1\AppData\Local\ORIGIN~1\instances", psi.Arguments);
        Assert.True(JavaPathEncoding.Representable(psi.Arguments, 1252));
    }

    // ---- LOC-3: 1.8.9's options.txt keeps its bytes ---------------------------------------------------------------

    [Fact]
    public void Settings_sync_keeps_a_1_8_9_options_file_in_its_own_charset()
    {
        var dir = Directory.CreateDirectory(Path.Combine(_root, "1.8.9")).FullName;
        var path = Path.Combine(dir, "options.txt");
        // 1.8.9 wrote this in Cp1252: "§bBlue 16x.zip" and "Pâck.zip" as single bytes 0xA7 and 0xE2.
        var original = Encoding.Latin1.GetBytes("fov:0.0\nresourcePacks:[\"\u00A7bBlue 16x.zip\",\"P\u00E2ck.zip\"]\nlastServer:caf\u00E9.example.net\n");
        File.WriteAllBytes(path, original);

        var map = OptionsTxtFormat.Instance.Apply(path, new Dictionary<string, string?> { ["fov"] = "0.5" }, new SyncInstance("1.8.9", dir));

        Assert.NotNull(map);
        var after = File.ReadAllBytes(path);
        Assert.NotEqual(original, after); // fov really changed
        var text = Encoding.Latin1.GetString(after);
        Assert.Contains("resourcePacks:[\"\u00A7bBlue 16x.zip\",\"P\u00E2ck.zip\"]", text);
        Assert.Contains("lastServer:caf\u00E9.example.net", text);
        Assert.DoesNotContain("\u00EF\u00BF\u00BD", text); // EF BF BD: U+FFFD written as UTF-8
        Assert.DoesNotContain("\u00C2\u00A7", text);      // C2 A7: "§" re-encoded as UTF-8
        // And reading it back (no version at hand) is not U+FFFD either.
        Assert.Equal("[\"\u00A7bBlue 16x.zip\",\"P\u00E2ck.zip\"]", OptionsTxtFormat.Instance.Read(path)!["resourcePacks"]);
    }

    [Fact]
    public void A_modern_options_file_stays_utf8()
    {
        var dir = Directory.CreateDirectory(Path.Combine(_root, "1.21.1")).FullName;
        var path = Path.Combine(dir, "options.txt");
        File.WriteAllText(path, "version:3955\nfov:0.0\nlastServer:café.example.net\n", new UTF8Encoding(false));

        OptionsTxtFormat.Instance.Apply(path, new Dictionary<string, string?> { ["fov"] = "0.5" }, new SyncInstance("1.21.1", dir));

        Assert.Contains("lastServer:café.example.net", File.ReadAllText(path, Encoding.UTF8));
    }

    // ---- LOC-6: one way to write a memory size ----------------------------------------------------------------------

    [Fact]
    public void Memory_sizes_use_a_dot_on_every_language()
    {
        var before = System.Globalization.CultureInfo.CurrentCulture;
        try
        {
            System.Globalization.CultureInfo.CurrentCulture = new System.Globalization.CultureInfo("de-DE");
            Assert.Equal("4.5 GB", GbText.FromMb(4608));
            Assert.Equal("4.5 GB", GbText.FromMb(4608, "0.##"));
            Assert.Equal("4 GB", GbText.FromMb(4096));
            Assert.Equal("1.5 GB", DiskSpace.Gb(1536L * 1024 * 1024));
        }
        finally
        {
            System.Globalization.CultureInfo.CurrentCulture = before;
        }
    }
}
