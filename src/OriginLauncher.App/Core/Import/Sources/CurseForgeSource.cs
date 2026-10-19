using System.Globalization;
using System.IO;
using System.Text.Json;
using OriginLauncher.App.Core.Import.Detection;

namespace OriginLauncher.App.Core.Import.Sources;

// The CurseForge app: <root>/Instances/<name>/minecraftinstance.json, and the instance folder
// IS the game dir. From the json: name, gameVersion ("1.21.1"), baseModLoader (an object whose
// name is "<loader>-<version>": forge- / neoforge- / fabric- / quilt-; null or {} for vanilla
// and tolerated), lastPlayed (ISO; "0001-01-01T00:00:00" = never) and playedCount.
// timePlayed has no known unit, so it is NOT used as playtime: the picker only gets
// recency (and the launch count as tie info). No pin exists.
// The root is %USERPROFILE%\curseforge\minecraft (or the Documents one); a custom location is
// something only Browse can find. A root given as the Instances folder itself also works.
//
// The json is the launcher's statement; SetupResolver cross-checks it with the instance's
// latest.log and mods. A modded instance whose baseModLoader is null names no loader: the
// log's loader is used, and is certain only when the mods agree.
public sealed class CurseForgeSource : IInstanceSource
{
    public SourceKind Kind => SourceKind.CurseForge;

    public Task DetectAsync(SourceRoots roots, SourceFs fs, ScanResult into, CancellationToken ct) =>
        Task.Run(() =>
        {
            if (string.IsNullOrWhiteSpace(roots.CurseForgeDir) || !Directory.Exists(roots.CurseForgeDir)) return;
            var instances = Path.Combine(roots.CurseForgeDir, "Instances");
            if (!Directory.Exists(instances)) instances = roots.CurseForgeDir;
            var members = new List<SetupMember>();
            foreach (var folder in fs.EnumerateDirectories(instances).OrderBy(d => d, StringComparer.OrdinalIgnoreCase))
            {
                ct.ThrowIfCancellationRequested();
                if (ReadMember(folder, fs) is { } member) members.Add(member);
            }
            SetupResolver.AddSetups(into, members, fs, ct);
        }, ct);

    /// <summary>One instance folder (the one holding minecraftinstance.json), or null when it isn't one. Also used by Browse.</summary>
    public static DetectedInstance? ReadInstance(string instanceDir, SourceFs fs, CancellationToken ct) =>
        ReadMember(instanceDir, fs) is { } member ? SetupResolver.ResolveOne(member, fs, ct) : null;

    /// <summary>The launcher's own description of one instance folder (not yet weighed against its log and mods).</summary>
    public static SetupMember? ReadMember(string instanceDir, SourceFs fs)
    {
        var file = Path.Combine(instanceDir, "minecraftinstance.json");
        if (!File.Exists(file)) return null;

        var folderName = Path.GetFileName(Path.TrimEndingDirectorySeparator(instanceDir));
        var instance = new DetectedInstance { Kind = SourceKind.CurseForge, Name = folderName, GameDir = instanceDir };
        var text = fs.ReadText(file);
        if (text == null) return new SetupMember(SourceHelpers.Unreadable(instance)) { NoInference = true };

        try
        {
            using var doc = JsonDocument.Parse(text, new JsonDocumentOptions { AllowTrailingCommas = true });
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return new SetupMember(instance);

            if (Str(root, "name") is { Length: > 0 } name) instance = instance with { Name = name };

            var mc = Str(root, "gameVersion");
            LoaderKind? loader = null;
            string? loaderVersion = null;
            if (root.TryGetProperty("baseModLoader", out var modLoader) && modLoader.ValueKind == JsonValueKind.Object)
            {
                mc ??= Str(modLoader, "minecraftVersion");
                if (Str(modLoader, "name") is { Length: > 0 } loaderName && ParseLoader(loaderName, out var kind, out var v))
                {
                    loader = kind;
                    loaderVersion = v;
                }
            }
            if (loader == null && root.TryGetProperty("isVanilla", out var vanilla) && vanilla.ValueKind == JsonValueKind.True)
                loader = LoaderKind.None; // a vanilla instance: "no loader" is a statement

            if (root.TryGetProperty("lastPlayed", out var played) && played.ValueKind == JsonValueKind.String
                && DateTimeOffset.TryParse(played.GetString(), CultureInfo.InvariantCulture,
                    DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var when)
                && when.Year > 1)
                instance = instance with { LastPlayed = when };
            if (root.TryGetProperty("playedCount", out var count) && count.ValueKind == JsonValueKind.Number && count.TryGetInt32(out var launches))
                instance = instance with { LaunchCount = launches };

            if (mc == null && loader == null) return new SetupMember(instance);

            // The json's mtime: when CurseForge last wrote the instance (EvidenceJudge V3 compares it with the log's).
            DateTimeOffset? changed = null;
            try { changed = new DateTimeOffset(File.GetLastWriteTimeUtc(file), TimeSpan.Zero); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
            var statement = new VersionEvidence(EvidenceSource.LauncherMetadata, mc, loader, loaderVersion, changed) { From = instance.Name };
            return new SetupMember(instance) { Metadata = new[] { statement } };
        }
        catch (JsonException)
        {
            return new SetupMember(instance); // damaged json: the folder's log and mods decide
        }
    }

    private static bool ParseLoader(string name, out LoaderKind loader, out string? version)
    {
        loader = LoaderKind.None;
        version = null;
        foreach (var (prefix, kind) in new[]
                 {
                     ("neoforge-", LoaderKind.NeoForge), ("forge-", LoaderKind.Forge),
                     ("fabric-", LoaderKind.Fabric), ("quilt-", LoaderKind.Quilt),
                 })
        {
            if (!name.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) continue;
            loader = kind;
            version = name[prefix.Length..];
            return true;
        }
        return false;
    }

    private static string? Str(JsonElement obj, string name) =>
        obj.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
}
