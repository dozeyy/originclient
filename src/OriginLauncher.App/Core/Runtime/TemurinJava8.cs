using System.Linq;
using System.Threading.Tasks;
using System.Threading;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Runtime;

/// <summary>One pinned Java build: where it comes from and the SHA-256 the file must have.</summary>
public sealed record JavaRuntimePin(string Folder, string Url, string Sha256, long SizeBytes);

/// <summary>
/// Eclipse Temurin 8 for 1.8.9-era versions, instead of Mojang's 2015 jre-legacy (8u51).
/// Pinned, never "latest": a new build is a code change with a new URL + hash.
/// Source (2026-10-20): Adoptium API
///   api.adoptium.net/v3/assets/latest/8/hotspot?architecture=x64&amp;image_type=jre&amp;os=windows&amp;vendor=eclipse
/// and the release's own .sha256.txt asset; both gave the same hash.
/// </summary>
public static class TemurinJava8
{
    public static readonly JavaRuntimePin Pin = new(
        Folder: "temurin-8u504-b01",
        Url: "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jre_x64_windows_hotspot_8u504b01.zip",
        Sha256: "82e2cdc6693737c5998445b31f69668fa0da77c7705121053f6508ac84961123",
        SizeBytes: 40104826);
}

/// <summary>Streams one URL to a file. Replaceable so tests never touch the network.</summary>
public delegate Task JavaDownload(string url, string destinationFile, IProgress<double>? fraction, CancellationToken ct);

/// <summary>
/// Puts a pinned Java under <c>OriginPaths.Runtimes</c> and returns its javaw.exe, or null when
/// it can't (offline, hash mismatch, bad zip, disk error). Null ALWAYS means "keep the Java the
/// launch already had" - a failure here never breaks a launch (Mandate 4).
///
/// Per-user, no admin, and nothing is ever run: download to a temp folder, verify SHA-256, unzip
/// to a temp folder, then rename the finished folder into place (atomic on one volume), so a
/// half-done install is never mistaken for a good one.
/// </summary>
public static class JavaRuntimeProvisioner
{
    private const string ReadyMarker = ".origin-verified";

    // One try per launcher session: a failing hash or a dead network must not cost every
    // later launch another 40 MB attempt. A restart tries again.
    private static readonly HashSet<string> FailedThisSession = new(StringComparer.Ordinal);

    /// <summary>The javaw.exe of an already-installed, verified runtime, or null. No network, no waiting.</summary>
    public static string? InstalledJavaw(JavaRuntimePin pin, string runtimesRoot)
    {
        var dir = Path.Combine(runtimesRoot, pin.Folder);
        var javaw = Path.Combine(dir, "bin", "javaw.exe");
        return File.Exists(javaw) && File.Exists(Path.Combine(dir, ReadyMarker)) ? javaw : null;
    }

    public static async Task<string?> EnsureAsync(
        JavaRuntimePin pin, string runtimesRoot, JavaDownload download,
        IProgress<string>? progress, CancellationToken ct)
    {
        if (InstalledJavaw(pin, runtimesRoot) is { } ready) return ready;
        lock (FailedThisSession) { if (FailedThisSession.Contains(pin.Url)) return null; }

        string? temp = null;
        try
        {
            SweepStaleTemp(runtimesRoot);
            temp = Path.Combine(runtimesRoot, ".tmp-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(temp);
            var zip = Path.Combine(temp, "java.zip");

            progress?.Report("Updating Java for this version...");
            var last = -1;
            var fraction = new Progress<double>(f =>
            {
                var pct = (int)(f * 100);
                if (pct / 5 == last / 5) return; // report every 5 %, not every chunk
                last = pct;
                progress?.Report($"Updating Java for this version... {pct}%");
            });
            await download(pin.Url, zip, fraction, ct).ConfigureAwait(false);

            if (!await HashMatchesAsync(zip, pin.Sha256, ct).ConfigureAwait(false))
                return Fail(pin); // never unzip a file whose hash is wrong

            var extracted = Path.Combine(temp, "x");
            // .NET's ExtractToDirectory refuses entries that would land outside the folder.
            ZipFile.ExtractToDirectory(zip, extracted);

            var root = FindRuntimeRoot(extracted);
            if (root == null) return Fail(pin);

            File.WriteAllText(Path.Combine(root, ReadyMarker), pin.Sha256);
            var target = Path.Combine(runtimesRoot, pin.Folder);
            try
            {
                Directory.Move(root, target);
            }
            catch (IOException) when (InstalledJavaw(pin, runtimesRoot) != null)
            {
                // Another launch finished the same install first: use theirs.
            }
            return InstalledJavaw(pin, runtimesRoot) ?? Fail(pin);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw; // the player cancelled the launch: not a failure to remember (a timeout is: it reaches Fail below)
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidDataException
            or HttpRequestException or TaskCanceledException or NotSupportedException)
        {
            return Fail(pin);
        }
        finally
        {
            if (temp != null) { try { Directory.Delete(temp, recursive: true); } catch { /* best effort */ } }
        }
    }

    // A launcher killed mid-download leaves its .tmp-* folder behind: drop the old ones.
    private static void SweepStaleTemp(string runtimesRoot)
    {
        try
        {
            if (!Directory.Exists(runtimesRoot)) return;
            foreach (var d in Directory.EnumerateDirectories(runtimesRoot, ".tmp-*"))
                if (DateTime.UtcNow - Directory.GetLastWriteTimeUtc(d) > TimeSpan.FromHours(1))
                    Directory.Delete(d, recursive: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { /* best effort */ }
    }

    /// <summary>The folder holding bin\javaw.exe: the extract root itself or its single sub-folder.</summary>
    public static string? FindRuntimeRoot(string extractedDir)
    {
        if (File.Exists(Path.Combine(extractedDir, "bin", "javaw.exe"))) return extractedDir;
        foreach (var sub in Directory.EnumerateDirectories(extractedDir))
            if (File.Exists(Path.Combine(sub, "bin", "javaw.exe"))) return sub;
        return null;
    }

    public static async Task<bool> HashMatchesAsync(string file, string expectedSha256, CancellationToken ct)
    {
        await using var s = File.OpenRead(file);
        var hash = Convert.ToHexString(await SHA256.HashDataAsync(s, ct).ConfigureAwait(false));
        return string.Equals(hash, expectedSha256, StringComparison.OrdinalIgnoreCase);
    }

    private static string? Fail(JavaRuntimePin pin)
    {
        lock (FailedThisSession) FailedThisSession.Add(pin.Url);
        return null;
    }

    /// <summary>Test hook: forget this session's failures.</summary>
    public static void ResetFailuresForTests() { lock (FailedThisSession) FailedThisSession.Clear(); }

    /// <summary>The real download: streamed to disk, capped at 5 minutes, progress as a 0..1 fraction.</summary>
    public static async Task HttpDownload(
        HttpClient http, string url, string destinationFile, IProgress<double>? fraction, CancellationToken ct)
    {
        using var cap = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cap.CancelAfter(TimeSpan.FromMinutes(5));
        using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cap.Token).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        var total = response.Content.Headers.ContentLength ?? 0;
        await using var src = await response.Content.ReadAsStreamAsync(cap.Token).ConfigureAwait(false);
        await using var dst = File.Create(destinationFile);
        var buffer = new byte[81920];
        long done = 0;
        int n;
        while ((n = await src.ReadAsync(buffer, cap.Token).ConfigureAwait(false)) > 0)
        {
            await dst.WriteAsync(buffer.AsMemory(0, n), cap.Token).ConfigureAwait(false);
            done += n;
            if (total > 0) fraction?.Report((double)done / total);
        }
    }
}

/// <summary>Which Java a legacy launch runs: the provisioned Temurin when it is ready, else null (= keep the existing one).</summary>
public static class LegacyJava
{
    public static Task<string?> ResolveAsync(HttpClient http, IProgress<string>? progress, CancellationToken ct) =>
        JavaRuntimeProvisioner.EnsureAsync(
            TemurinJava8.Pin, OriginPaths.Runtimes,
            (url, dest, frac, token) => JavaRuntimeProvisioner.HttpDownload(http, url, dest, frac, token),
            progress, ct);
}
