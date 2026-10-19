using System.IO;
using System.Security.Cryptography;
using OriginLauncher.App.Core.GameFolders;

namespace OriginLauncher.App.Core.Import.GameFiles;

// Game-file reuse (D25-D28): the import copies the files Minecraft would otherwise download
// from other launchers on this PC, so the first Play is fast. The rules, all of them about safety:
//  * only what the target version needs, from Mojang's own metadata (client jar, Windows
//    libraries + natives, the asset index and its objects, the Java runtime component) —
//    Fabric loader libraries and profile jsons have no hash to check, so they are never copied;
//  * COPY, never hardlink and never point at the source: a hardlink shares bytes with the other
//    launcher, and CmlLib downloads in place into whatever folder it is pointed at;
//  * every file is verified by SHA-1 before it is used — at plan time to count honestly, and
//    again while it is copied; a corrupt or truncated candidate is simply not copied and counts
//    as "to download";
//  * a file Origin already has is never touched;
//  * origin-verified.json is never created or changed: the first Play then does CmlLib's full
//    checksum pass over everything, copied files included;
//  * the version's provisioning gate is held while the files are written (IVersionGate);
//  * only small metadata documents are fetched (MetadataClient), never a game file.
public sealed class GameFileContributor : IVersionWideContributor
{
    private const int ChunkSize = 128; // files journaled per write (a journal rewrite per file would be quadratic)

    private readonly GameFileRoots _roots;
    private readonly IMetadataHttp _http;
    private readonly IVersionGate? _gate;

    public GameFileContributor(GameFileRoots roots, IMetadataHttp http, IVersionGate? gate = null)
    {
        _roots = roots;
        _http = http;
        _gate = gate;
    }

    private sealed record Item(string Source, string Dest, string Rel, string Sha1, long Size);

    public async Task ContributeAsync(ImportPlan plan, ImportRequest request, SourceFs fs, CancellationToken ct)
    {
        if (!plan.CanApply) return;
        var target = plan.TargetVersion;

        // A bulk plan shares one metadata session across its versions (the version list is fetched once).
        var http = request?.Metadata?.Over(_http) ?? _http;
        var set = await new MojangMetadata(new MetadataClient(http)).GetRequiredSetAsync(target, ct).ConfigureAwait(false);
        if (set.Unavailable)
        {
            plan.GameFiles = new GameFilePlanStats(0, 0, 0, 0, 0, MetadataUnavailable: true, JavaSkipped: false);
            plan.Lines.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.GameFile, "Game files",
                "Couldn't reach Mojang's version list, so nothing was reused. Minecraft downloads what it needs the first time you press Play."));
            return;
        }

        var versionRoot = Path.GetFullPath(InstanceLayout.VersionRoot(_roots.InstancesRoot, target));
        var sources = GameFileSource.Build(_roots, target);
        var reused = new List<Item>();
        long toDownloadBytes = 0;
        var toDownloadCount = 0;
        var present = 0;

        foreach (var file in set.Files)
        {
            ct.ThrowIfCancellationRequested();
            var dest = Path.GetFullPath(Path.Combine(versionRoot, file.RelPath.Replace('/', Path.DirectorySeparatorChar)));
            // Metadata is data from outside: a path that climbs out of the version folder is never written.
            if (!dest.StartsWith(versionRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) continue;
            if (File.Exists(dest)) { present++; continue; }

            var good = FindVerified(file, target, sources, fs, ct);
            if (good != null)
            {
                reused.Add(new Item(good, dest, file.RelPath, file.Sha1, file.Size));
                plan.Lines.Add(new SummaryLine(SummarySection.ReusedGameFiles, PlanItemKind.GameFile, file.RelPath, null, file.Size));
            }
            else
            {
                toDownloadCount++;
                toDownloadBytes += file.Size;
            }
        }

        var reusedBytes = reused.Sum(i => i.Size);
        plan.GameFiles = new GameFilePlanStats(reused.Count, reusedBytes, toDownloadCount, toDownloadBytes, present,
            MetadataUnavailable: false, JavaSkipped: set.JavaSkipped);

        if (toDownloadCount > 0)
            plan.Lines.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.GameFile,
                $"{toDownloadCount} game {(toDownloadCount == 1 ? "file" : "files")} not found on this PC",
                $"about {ImportSummary.Megabytes(toDownloadBytes)} will download the first time you press Play."));
        if (set.JavaSkipped)
            plan.Lines.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.GameFile, "Java runtime",
                "Java will download on first Play."));

        if (reused.Count == 0) return;
        plan.Ops.Add(new PlannedOp
        {
            Kind = OpKind.Custom, Item = PlanItemKind.GameFile, Label = "Game files", Dest = versionRoot, Bytes = reusedBytes,
            Run = ctx => Copy(ctx, reused, target),
        });
        if (_gate != null) plan.ProvisioningGate = _gate;
    }

    // The first candidate, in any source, whose bytes really hash to the metadata's SHA-1. A candidate
    // of the wrong size is dismissed without being read; a read that fails is simply not a match.
    private static string? FindVerified(RequiredFile file, string target, List<GameFileSource> sources, SourceFs fs, CancellationToken ct)
    {
        foreach (var source in sources)
            foreach (var candidate in source.Candidates(file, target))
            {
                if (!File.Exists(candidate)) continue;
                if (file.Size > 0 && ImportFiles.SizeOrNull(candidate) != file.Size) continue; // gone or unreadable: no match
                // The SHA-1 proves the content, so the credential NAME pattern isn't asked (it would refuse
                // com/mojang/authlib); launcher credential stores are still never opened, and are never required.
                if (!fs.AllowVerifiedGameFile(candidate)) continue;
                if (Sha1Matches(fs, candidate, file.Sha1, ct)) return candidate;
            }
        return null;
    }

    private static bool Sha1Matches(SourceFs fs, string path, string sha1, CancellationToken ct)
    {
        using var stream = fs.OpenVerifiedGameFile(path);
        if (stream == null) return false;
        try
        {
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA1);
            var buffer = new byte[81920];
            int n;
            while ((n = stream.Read(buffer, 0, buffer.Length)) > 0)
            {
                ct.ThrowIfCancellationRequested();
                hash.AppendData(buffer, 0, n);
            }
            return Convert.ToHexString(hash.GetHashAndReset()).Equals(sha1, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    // Apply: temp copy (hashed on the way) then rename, journaled in chunks. Bytes that no longer match
    // are dropped (a source that changed since the plan) and counted as "to download", never failed on.
    private static void Copy(ImportOpContext ctx, List<Item> items, string target)
    {
        var staleCount = 0;
        long staleBytes = 0;
        var done = 0;
        for (var start = 0; start < items.Count; start += ChunkSize)
        {
            ctx.Ct.ThrowIfCancellationRequested();
            var chunk = items.Skip(start).Take(ChunkSize).Where(i => !File.Exists(i.Dest)).ToList();
            ctx.Tx.JournalNewFiles(chunk.Select(i => i.Dest).ToList());
            foreach (var item in chunk)
            {
                ctx.Report(ImportStage.GameFiles, done++, items.Count, item.Rel);
                ctx.Ct.ThrowIfCancellationRequested();
                var temp = ctx.Tx.NewStagingPath(Path.GetFileName(item.Dest));
                if (!CopyVerified(ctx, item, temp))
                {
                    ctx.Tx.ForgetNewFile(item.Dest);
                    staleCount++;
                    staleBytes += item.Size;
                    continue;
                }
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(item.Dest)!);
                    File.Move(temp, item.Dest, overwrite: false); // never over a file that appeared meanwhile
                    ctx.Tx.Log($"copied {target}/{item.Rel}");
                }
                catch (IOException) when (File.Exists(item.Dest))
                {
                    ctx.Tx.ForgetNewFile(item.Dest); // it is someone else's file now: untouched, and not ours to undo
                    File.Delete(temp);
                }
            }
        }
        if (staleCount > 0)
            ctx.Extra.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.GameFile,
                $"{staleCount} game {(staleCount == 1 ? "file" : "files")} changed while importing",
                $"about {ImportSummary.Megabytes(staleBytes)} will download the first time you press Play."));
    }

    private static bool CopyVerified(ImportOpContext ctx, Item item, string temp)
    {
        if (!ctx.Fs.AllowVerifiedGameFile(item.Source)) return false;
        try
        {
            using var input = new FileStream(item.Source, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA1);
            using (var output = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                var buffer = new byte[81920];
                int n;
                while ((n = input.Read(buffer, 0, buffer.Length)) > 0)
                {
                    ctx.Ct.ThrowIfCancellationRequested();
                    hash.AppendData(buffer, 0, n);
                    output.Write(buffer, 0, n);
                }
            }
            if (Convert.ToHexString(hash.GetHashAndReset()).Equals(item.Sha1, StringComparison.OrdinalIgnoreCase)) return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // a source that vanished or can't be read: not a match
        }
        try { File.Delete(temp); } catch (IOException) { }
        return false;
    }
}
