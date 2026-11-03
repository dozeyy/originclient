using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Updates;

// Changed-files-only updates (Will, 2026-11-03). The release zip is ~266 MB and mostly mod jars that don't change
// between releases. Instead of downloading it whole, this builds the same staged folder the full zip would unpack to:
//  1. every file the list names that is already installed with the right SHA-256 is copied from the install folder
//     (hashed while it is copied, so the staged bytes are the verified ones);
//  2. every other file is fetched out of the release zip itself: one HTTP Range request per run of neighbouring
//     entries, inflated as it streams in, and checked against the list's size and SHA-256.
// The stage ends up a complete copy of the release, so the swap script is the same one the full zip uses. Files a
// release dropped are not staged (the swap overlays the stage onto the install folder, exactly as with a full zip).
//
// Never weaker than the full download: the list's own hash was checked (.sha256 sidecar) and the list is tied to the
// zip hash the launcher trusts (DeltaManifest.Parse), and every staged file matches the list's SHA-256. ANY surprise
// (a server that answers 200 instead of 206, a wrong Content-Range, an encoded body, a short body, a hash mismatch,
// a stall, an I/O error) throws DeltaUnavailableException and UpdateService downloads the full zip instead.
// Pure: an HttpClient, a URL and folders in, so the tests drive it against a loopback server.
public static class DeltaStager
{
    /// <summary>Entries closer than this in the zip are fetched in one request (fewer round trips beats a few KB).</summary>
    public const long MergeGap = 256 * 1024;

    /// <summary>Past this share of the zip, the delta saves little: the full download (one stream, one hash) is used.</summary>
    public const double MaxFetchFraction = 0.75;

    /// <summary>Range requests in flight at once.</summary>
    public const int Parallel = 3;

    public sealed record Result(int ReusedFiles, int FetchedFiles, long FetchedBytes, int Requests);

    /// <summary>A run of neighbouring entries fetched with one request: [Start, End) in the zip.</summary>
    public sealed record FetchRange(long Start, long End, IReadOnlyList<DeltaFile> Files)
    {
        public long Length => End - Start;
    }

    /// <summary>
    /// Groups the files to fetch (any order) into requests: sorted by offset, neighbours at most
    /// <paramref name="mergeGap"/> bytes apart share one request.
    /// </summary>
    public static List<FetchRange> PlanRanges(IEnumerable<DeltaFile> toFetch, long mergeGap = MergeGap)
    {
        var ranges = new List<FetchRange>();
        List<DeltaFile>? group = null;
        long start = 0, end = 0;
        foreach (var f in toFetch.OrderBy(f => f.Offset))
        {
            if (group != null && f.Offset - end <= mergeGap)
            {
                group.Add(f);
                end = Math.Max(end, f.Offset + f.CompressedSize);
                continue;
            }
            if (group != null) ranges.Add(new FetchRange(start, end, group));
            group = [f];
            start = f.Offset;
            end = f.Offset + f.CompressedSize;
        }
        if (group != null) ranges.Add(new FetchRange(start, end, group));
        return ranges;
    }

    /// <summary>
    /// Builds the complete release in <paramref name="stageDir"/> (created; it must not already hold files). Files whose
    /// copy in <paramref name="installDir"/> matches the list are reused; the rest come from <paramref name="zipUrl"/> by
    /// Range request. <paramref name="onProgress"/> gets (bytes fetched so far, bytes to fetch) once the plan is known.
    /// Throws <see cref="DeltaUnavailableException"/> for anything that should fall back to the full zip, and
    /// <see cref="OperationCanceledException"/> only when <paramref name="ct"/> was cancelled.
    /// </summary>
    public static async Task<Result> StageAsync(HttpClient http, string zipUrl, DeltaManifest manifest, string? installDir,
        string stageDir, Action<long, long>? onProgress, TimeSpan stallTimeout, CancellationToken ct,
        double maxFetchFraction = MaxFetchFraction, long mergeGap = MergeGap)
    {
        try
        {
            Directory.CreateDirectory(stageDir);
            if (Directory.EnumerateFileSystemEntries(stageDir).Any()) throw new DeltaUnavailableException("the stage folder is not empty");

            // 1. Reuse what is installed and still right.
            var toFetch = new List<DeltaFile>();
            var reused = 0;
            foreach (var f in manifest.Files)
            {
                ct.ThrowIfCancellationRequested();
                var dest = DeltaManifest.Resolve(stageDir, f.Path);
                if (f.Size == 0)
                {
                    // Nothing to fetch (a stored empty file has no bytes in the zip: its range would be X-(X-1)).
                    // DeltaManifest.Parse already checked its SHA-256 is the empty one.
                    Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                    await File.WriteAllBytesAsync(dest, Array.Empty<byte>(), ct).ConfigureAwait(false);
                    reused++;
                    continue;
                }
                if (installDir != null && await TryReuseAsync(DeltaManifest.Resolve(installDir, f.Path), dest, f, ct).ConfigureAwait(false))
                    reused++;
                else
                    toFetch.Add(f);
            }

            // 2. Plan the requests; a release that changed nearly everything is cheaper as one full download.
            var ranges = PlanRanges(toFetch, mergeGap);
            var total = ranges.Sum(r => r.Length);
            if (total > manifest.ZipSize * maxFetchFraction)
                throw new DeltaUnavailableException($"{total} of {manifest.ZipSize} bytes changed, the full download is simpler");
            onProgress?.Invoke(0, total);

            // 3. Fetch them, a few at a time; the first failure stops the rest.
            long received = 0;
            // The failure that stopped the others, recorded the moment it happened: siblings cancelled because of it
            // fail too (cancelled, or a read aborted mid-body), and must not be what the log says went wrong.
            var firstFailure = new StrongBox<Exception?>();
            using var stop = CancellationTokenSource.CreateLinkedTokenSource(ct);
            using var slots = new SemaphoreSlim(Parallel);
            var tasks = ranges.Select(async range =>
            {
                await slots.WaitAsync(stop.Token).ConfigureAwait(false);
                try
                {
                    await FetchRangeAsync(http, zipUrl, manifest.ZipSize, range, stageDir,
                        n => onProgress?.Invoke(Interlocked.Add(ref received, n), total), stallTimeout, stop.Token).ConfigureAwait(false);
                }
                catch (Exception e)
                {
                    lock (firstFailure)
                        if (!stop.IsCancellationRequested) firstFailure.Value ??= e;
                    stop.Cancel();
                    throw;
                }
                finally
                {
                    slots.Release();
                }
            }).ToList();
            try
            {
                await Task.WhenAll(tasks).ConfigureAwait(false);
            }
            catch when (!ct.IsCancellationRequested)
            {
                // Report the failure that came first, not a sibling's "cancelled because another failed".
                Exception? first;
                lock (firstFailure) first = firstFailure.Value;
                first ??= tasks.Where(t => t.IsFaulted).Select(t => t.Exception!.InnerException!)
                    .FirstOrDefault(e => e is not OperationCanceledException);
                if (first is DeltaUnavailableException) throw first;
                throw new DeltaUnavailableException(first?.Message ?? "a request was cancelled", first);
            }

            foreach (var d in manifest.Dirs)
                Directory.CreateDirectory(DeltaManifest.Resolve(stageDir, d));
            return new Result(reused, toFetch.Count, total, ranges.Count);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (DeltaUnavailableException)
        {
            throw;
        }
        catch (Exception e)
        {
            throw new DeltaUnavailableException($"{e.GetType().Name}: {e.Message}", e);
        }
    }

    /// <summary>
    /// Re-checks a stage built earlier (it can sit on disk for hours before the player clicks Update): exactly the
    /// listed files, each with the listed size and SHA-256. Anything else is an <see cref="UpdateVerificationException"/>,
    /// the same failure as a zip that changed on disk.
    /// </summary>
    public static async Task VerifyStagedAsync(DeltaManifest manifest, string stageDir, CancellationToken ct)
    {
        var expected = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var f in manifest.Files)
        {
            var path = DeltaManifest.Resolve(stageDir, f.Path);
            expected.Add(Path.GetFullPath(path));
            var info = new FileInfo(path);
            if (!info.Exists || info.Length != f.Size || await HashFileAsync(path, ct).ConfigureAwait(false) != f.Sha256)
                throw new UpdateVerificationException("the prepared update changed on disk (it failed its integrity check)");
        }
        foreach (var file in Directory.EnumerateFiles(stageDir, "*", SearchOption.AllDirectories))
            if (!expected.Contains(Path.GetFullPath(file)))
                throw new UpdateVerificationException("the prepared update has a file it should not have");
    }

    private static async Task<bool> TryReuseAsync(string installed, string dest, DeltaFile f, CancellationToken ct)
    {
        try
        {
            var info = new FileInfo(installed);
            if (!info.Exists || info.Length != f.Size) return false;
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            // The running exe and its neighbours are open: read with every share flag, never lock them.
            await using (var source = new FileStream(installed, FileMode.Open, FileAccess.Read,
                             FileShare.ReadWrite | FileShare.Delete, 1 << 16, useAsync: true))
            {
                var (length, sha) = await CopyHashedAsync(source, dest, f.Size, ct).ConfigureAwait(false);
                if (length == f.Size && sha == f.Sha256) return true;
            }
            File.Delete(dest);
            return false;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Locked or unreadable: just fetch it.
            try { File.Delete(dest); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            return false;
        }
    }

    private static async Task FetchRangeAsync(HttpClient http, string zipUrl, long zipSize, FetchRange range, string stageDir,
        Action<long> onBytes, TimeSpan stallTimeout, CancellationToken ct)
    {
        using var stall = CancellationTokenSource.CreateLinkedTokenSource(ct);
        stall.CancelAfter(stallTimeout);
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, zipUrl);
            request.Headers.Range = new RangeHeaderValue(range.Start, range.End - 1);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, stall.Token).ConfigureAwait(false);
            // 200 means the server ignored Range and is sending the whole zip: not this path's job.
            if (response.StatusCode != HttpStatusCode.PartialContent)
                throw new DeltaUnavailableException($"the server answered {(int)response.StatusCode} to a Range request");
            var cr = response.Content.Headers.ContentRange;
            if (cr is not { HasRange: true, HasLength: true } || cr.Unit != "bytes"
                || cr.From != range.Start || cr.To != range.End - 1 || cr.Length != zipSize)
                throw new DeltaUnavailableException($"the server sent another range ({cr}) than asked (bytes {range.Start}-{range.End - 1}/{zipSize})");
            if (response.Content.Headers.ContentEncoding.Count > 0)
                throw new DeltaUnavailableException("the server encoded the range");

            await using var body = await response.Content.ReadAsStreamAsync(stall.Token).ConfigureAwait(false);
            var counted = new CountingStream(body, n =>
            {
                stall.CancelAfter(stallTimeout); // bytes are flowing: restart the stall clock
                onBytes(n);
            });
            var position = range.Start;
            foreach (var f in range.Files)
            {
                await SkipAsync(counted, f.Offset - position, stall.Token).ConfigureAwait(false);
                await ExtractAsync(counted, f, DeltaManifest.Resolve(stageDir, f.Path), stall.Token).ConfigureAwait(false);
                position = f.Offset + f.CompressedSize;
            }
            await SkipAsync(counted, range.End - position, stall.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new DeltaUnavailableException("the connection stalled");
        }
    }

    // One entry: exactly CompressedSize bytes off the stream (the bound keeps the inflater from reading into the next
    // entry), inflated or copied to dest, then the size and SHA-256 must be the list's.
    private static async Task ExtractAsync(Stream source, DeltaFile f, string dest, CancellationToken ct)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        var bounded = new BoundedStream(source, f.CompressedSize);
        long length;
        string sha;
        if (f.Method == DeltaFile.Deflate)
        {
            await using var inflate = new DeflateStream(bounded, CompressionMode.Decompress, leaveOpen: true);
            (length, sha) = await CopyHashedAsync(inflate, dest, f.Size, ct).ConfigureAwait(false);
        }
        else
        {
            (length, sha) = await CopyHashedAsync(bounded, dest, f.Size, ct).ConfigureAwait(false);
        }
        await SkipAsync(bounded, bounded.Remaining, ct).ConfigureAwait(false);
        if (length != f.Size || sha != f.Sha256)
        {
            try { File.Delete(dest); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            throw new DeltaUnavailableException($"{f.Path} did not match its SHA-256");
        }
    }

    // Copies and hashes in one pass. Stops one byte past `limit`, so a stream that inflates far beyond the size the
    // list promises can't fill the disk (the caller then sees the wrong length and refuses the file).
    private static async Task<(long Length, string Sha256)> CopyHashedAsync(Stream source, string dest, long limit, CancellationToken ct)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        long length = 0;
        await using (var file = new FileStream(dest, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16, useAsync: true))
        {
            var buffer = new byte[1 << 16];
            int read;
            while (length <= limit && (read = await source.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
            {
                await file.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                hash.AppendData(buffer, 0, read);
                length += read;
            }
        }
        return (length, Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant());
    }

    private static async Task<string> HashFileAsync(string path, CancellationToken ct)
    {
        await using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16, useAsync: true);
        return Convert.ToHexString(await SHA256.HashDataAsync(file, ct).ConfigureAwait(false)).ToLowerInvariant();
    }

    private static async Task SkipAsync(Stream source, long count, CancellationToken ct)
    {
        if (count < 0) throw new DeltaUnavailableException("the zip's entries are out of order");
        var buffer = new byte[(int)Math.Min(count, 1 << 16)];
        while (count > 0)
        {
            var read = await source.ReadAsync(buffer.AsMemory(0, (int)Math.Min(count, buffer.Length)), ct).ConfigureAwait(false);
            if (read == 0) throw new DeltaUnavailableException("the range ended early");
            count -= read;
        }
    }

    // Reads at most `remaining` bytes of the inner stream; ending before that is a short body.
    private sealed class BoundedStream(Stream inner, long length) : Stream
    {
        public long Remaining { get; private set; } = length;

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
        {
            if (Remaining == 0 || buffer.Length == 0) return 0;
            var read = await inner.ReadAsync(buffer[..(int)Math.Min(buffer.Length, Remaining)], ct).ConfigureAwait(false);
            if (read == 0) throw new DeltaUnavailableException("the range ended early");
            Remaining -= read;
            return read;
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
            ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();

        public override int Read(byte[] buffer, int offset, int count) => ReadAsync(buffer, offset, count, default).GetAwaiter().GetResult();

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    // Tells the caller how many bytes each read brought (progress + the stall clock).
    private sealed class CountingStream(Stream inner, Action<long> onBytes) : Stream
    {
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
        {
            var read = await inner.ReadAsync(buffer, ct).ConfigureAwait(false);
            if (read > 0) onBytes(read);
            return read;
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
            ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();

        public override int Read(byte[] buffer, int offset, int count) => ReadAsync(buffer, offset, count, default).GetAwaiter().GetResult();

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
